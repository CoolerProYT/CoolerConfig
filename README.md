# CoolerConfig

A multiloader (Fabric + NeoForge) configuration library for Minecraft, built on
[Night-Config](https://github.com/TheElectronWill/night-config).

Supports **TOML**, **HOCON**, **JSON**, and **JSON5** with side-aware loading, comments, validation,
hot-reload, [Codec](#codec-backed-entries-structured-data)-backed structured data, and
lifecycle-driven reloading.

```java
ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
        .comment("MyMod configuration");

ENABLE = builder.defineBoolean("general.enable",   true,        "Enable all features");
COUNT  = builder.defineInt    ("general.maxItems", 64, 1, 1000, "Maximum item count");

CONFIG = builder.build();
```

```toml
# MyMod configuration
[general]
	# Enable all features
	enable = true
	# Maximum item count
	maxItems = 64
```

Read it anywhere, with no string paths and no casting:

```java
if (MyModConfig.ENABLE.get()) {
    spawn(MyModConfig.COUNT.get());
}
```

---

## Contents

- [Installation](#installation)
- [Quick start](#quick-start)
- [Config formats](#config-formats)
- [Config sides](#config-sides)
- [File names and locations](#file-names-and-locations)
- [Validation](#validation)
- [Codec-backed entries (structured data)](#codec-backed-entries-structured-data)
- [Writing values back](#writing-values-back)
- [Reload listeners](#reload-listeners)
- [Hot-reload (file watching)](#hot-reload-file-watching)
- [Building a config screen](#building-a-config-screen)
- [Error handling](#error-handling)
- [Complete example](#complete-example)

---

## Installation

CoolerConfig is a **standalone mod**, not a shadeable utility. Players must install it alongside
your mod — do not `include` / `jarJar` it into your own jar.

### 1 — Add the Maven repository

```groovy
repositories {
    maven {
        name = 'CoolerProMC'
        url = 'https://maven.coolerpromc.com/releases'
    }
}
```

### 2 — Declare the compile dependency

Artifact IDs follow the pattern `coolerconfig-<loader>-<minecraft_version>`.

**Common** (`common/build.gradle`):
```groovy
dependencies {
    compileOnly "com.coolerpromc.coolerconfig:coolerconfig-common-26.1.2:<version>"
}
```

**Fabric** (`fabric/build.gradle`):
```groovy
dependencies {
    modImplementation "com.coolerpromc.coolerconfig:coolerconfig-fabric-26.1.2:<version>"
}
```

**NeoForge** (`neoforge/build.gradle`):
```groovy
dependencies {
    implementation "com.coolerpromc.coolerconfig:coolerconfig-neoforge-26.1.2:<version>"
}
```

Night-Config ships inside the CoolerConfig jar (Fabric) or is provided by the loader and JiJ'd
(NeoForge), so you never declare it yourself.

Browse `https://maven.coolerpromc.com/releases/com/coolerpromc/coolerconfig/` for available versions.

### 3 — Declare the mod dependency

Tell the loader that CoolerConfig must be present and must initialise **before** your mod, so that
its platform services are ready when you build your spec.

**`fabric.mod.json`:**
```json
"depends": {
    "coolerconfig": ">=26.1.2.0"
}
```

**`META-INF/neoforge.mods.toml`:**
```toml
[[dependencies.<your_mod_id>]]
    modId = "coolerconfig"
    type = "required"
    versionRange = "[26.1.2.0,)"
    ordering = "AFTER"
    side = "BOTH"
```

`ordering = "AFTER"` is what guarantees CoolerConfig's `@Mod` constructor runs before yours.

---

## Quick start

Build your spec during your mod's **init phase** — inside `onInitialize()` (Fabric) or your `@Mod`
constructor (NeoForge). Do **not** build it in a `static {}` block: the loader has not yet told us
where the config directory is at class-load time.

Each `define*` call returns a typed `ConfigValue<T>` handle. Keep those as static fields and read
through them; you never touch string paths again.

```java
public final class MyModConfig {

    public static ConfigValue<Boolean>      ENABLE_FEATURE;
    public static ConfigValue<Integer>      MAX_ITEMS;
    public static ConfigValue<Double>       SCALE;
    public static ConfigValue<String>       MESSAGE;
    public static ConfigValue<Difficulty>   DIFFICULTY;
    public static ConfigValue<List<String>> BLACKLIST;

    public static ConfigSpec CONFIG;

    public static void init() {
        ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
                .side(ConfigSide.COMMON)          // optional — COMMON is the default
                .comment("MyMod configuration");  // file header comment

        ENABLE_FEATURE = builder.defineBoolean("general.enableFeature", true,           "Enable the main feature");
        MAX_ITEMS      = builder.defineInt    ("general.maxItems",      64, 1, 1000,    "Maximum item count");
        SCALE          = builder.defineDouble ("general.scale",         1.5, 0.1, 10.0, "Scale multiplier");
        MESSAGE        = builder.defineString ("general.message",       "Hello!",       "Chat message");
        DIFFICULTY     = builder.defineEnum   ("general.difficulty",    Difficulty.NORMAL, "Difficulty level");
        BLACKLIST      = builder.defineList   ("general.blacklist",     List.of("minecraft:dirt"), "Blacklisted items",
                                               v -> v instanceof String s && s.contains(":"));

        CONFIG = builder.build(); // creates the file if absent, loads it, registers for auto-reload
    }
}
```

Then call `MyModConfig.init()` from your mod initialiser, and read values anywhere afterwards:

```java
boolean      on   = MyModConfig.ENABLE_FEATURE.get();
int          max  = MyModConfig.MAX_ITEMS.get();
Difficulty   diff = MyModConfig.DIFFICULTY.get();
List<String> bl   = MyModConfig.BLACKLIST.get();
```

Entries appear in the file in the order you declared them.

---

## Config formats

| Constant | Extension | Comments | Comment syntax |
|---|---|---|---|
| `ConfigFormat.TOML` | `.toml` | Yes — per-entry and header | `# comment` |
| `ConfigFormat.HOCON` | `.conf` | Yes — per-entry and header | `# comment` |
| `ConfigFormat.JSON` | `.json` | **No** | N/A |
| `ConfigFormat.JSON5` | `.json5` | Yes — per-entry and header | `// comment` |

**TOML** is the conventional choice for a Minecraft mod; both loaders use it for their own configs.

**JSON** cannot represent comments, so every comment you write is silently dropped — a poor choice
for a file players edit by hand. Reach for **JSON5** if you want JSON syntax *and* comments: files
are written as pretty-printed, still-valid JSON, and on read the full JSON5 syntax (comments,
trailing commas, single quotes, unquoted keys) is accepted.

> CoolerConfig rewrites the whole file whenever the spec and the file disagree. Comments come from
> your spec, so hand-written comments added to the file are not preserved.

---

## Config sides

| Value | Loads on | Typical use |
|---|---|---|
| `ConfigSide.COMMON` | Client **and** server | Most configs — the default |
| `ConfigSide.SERVER` | Client **and** dedicated server | Game-balance settings (server-authoritative) |
| `ConfigSide.CLIENT` | Physical client only | HUD, keybinds, visual settings |

`CLIENT` configs are skipped on a dedicated server: no file is created, and the entries keep their
defaults.

These are *physical* sides. In single-player the client process runs the integrated server too, so
all three load.

> `SERVER` configs are read from whichever machine runs the server process. They are **not** synced
> from server to client — a remote multiplayer client still reads its own local file. If you need
> real sync, implement it yourself on top of `ConfigSpec`.

---

## File names and locations

By default the file is `<modId>-<side>.<ext>` in the loader's config directory:

| Builder call | File |
|---|---|
| `builder("mymod", TOML)` (COMMON default) | `config/mymod-common.toml` |
| `builder("mymod", TOML).side(CLIENT)` | `config/mymod-client.toml` |
| `builder("mymod", HOCON).side(SERVER)` | `config/mymod-server.conf` |

Override the name when you want several configs of the same side:

```java
ConfigSpec.builder("mymod", ConfigFormat.TOML).suffix("graphics")     // config/mymod-graphics.toml
ConfigSpec.builder("mymod", ConfigFormat.TOML).fileName("mymod-perf") // config/mymod-perf.toml
ConfigSpec.builder("mymod", ConfigFormat.TOML).fileName("mymod/main") // config/mymod/main.toml
```

`suffix()` keeps the `<modId>-` prefix; `fileName()` replaces the whole base name and may contain
`/` to nest into a subdirectory. Directories are created for you. The resolved path is available
via `spec.getFilePath()`.

Two specs may not share one file name — that would have them overwrite each other's keys on every
save, so it throws at build time instead.

---

## Validation

A value in the file that fails validation is reset to its default, logged at `WARN`, and written
back:

```
[CoolerConfig] 'general.maxItems': invalid value '9999', resetting to default '64'
```

| Method | Accepts |
|---|---|
| `defineBoolean(path, default, comment)` | a boolean |
| `defineInt(path, default, min, max, comment)` | `min <= value <= max` |
| `defineLong(path, default, min, max, comment)` | `min <= value <= max` |
| `defineFloat(path, default, min, max, comment)` | `min <= value <= max` |
| `defineDouble(path, default, min, max, comment)` | `min <= value <= max` |
| `defineString(path, default, comment)` | a string |
| `defineString(path, default, comment, validator)` | a string passing your predicate |
| `defineEnum(path, default, comment)` | a name matching a constant of the enum |
| `defineList(path, default, comment)` | a list (elements unchecked) |
| `defineList(path, default, comment, elementValidator)` | a list whose every element passes your predicate |
| `defineMap(path, default, comment)` | a map |
| `define(path, default, comment, validator)` | anything passing your predicate |
| `defineCodec(path, codec, default, comment)` | anything the codec decodes |

```java
// only allow specific values
builder.defineString("mode.type", "normal", "Game mode (normal/hard/extreme)",
        v -> v instanceof String s && Set.of("normal", "hard", "extreme").contains(s));

// validate every element of a list
builder.defineList("general.blacklist", List.of("minecraft:dirt"), "Blacklisted item IDs",
        v -> v instanceof String s && s.contains(":"));
```

**Your default must satisfy your own validator.** `defineInt("x", 999, 1, 100, ...)` throws
`IllegalArgumentException` immediately rather than creating an entry that can never hold a valid
value.

Prefer the element-validating `defineList` overload for anything a player edits. An unchecked list
hands your code whatever the player typed, and the resulting `ClassCastException` surfaces far away
from the config.

---

## Codec-backed entries (structured data)

For records, nested objects, lists of objects, or maps with mixed value types, use `defineCodec`
with a Mojang [`Codec`](https://forge.gemwire.uk/wiki/Codecs). The codec handles both directions and
**is** the validator: a value that fails to decode logs a warning and resets to the default.

Codec entries work in **all four formats**.

### Structured objects

```java
public record Boss(String name, int health, double scale) {
    public static final Codec<Boss> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Boss::name),
            Codec.INT.fieldOf("health").forGetter(Boss::health),
            Codec.DOUBLE.fieldOf("scale").forGetter(Boss::scale)
    ).apply(i, Boss::new));
}

BOSS = builder.defineCodec("boss", Boss.CODEC, new Boss("Ender Dragon", 200, 1.0), "Boss settings");
```

```toml
# Boss settings
[boss]
	name = "Ender Dragon"
	health = 200
	scale = 1.0
```

It reads back as a real `Boss`, with genuine `int` and `double` fields — no casting:

```java
int health = BOSS.get().health();
```

Nesting works to any depth. `Boss.CODEC.listOf()` gives you a list of objects (a TOML
array-of-tables / JSON array of objects).

### Maps with mixed value types

When keys are dynamic and each value may independently be a boolean, number, or string:

| Constant | Type |
|---|---|
| `CoolerCodecs.PRIMITIVE` | `Codec<Object>` — any boolean, number, or string |
| `CoolerCodecs.PRIMITIVE_MAP` | `Codec<Map<String, Object>>` |
| `CoolerCodecs.PRIMITIVE_LIST` | `Codec<List<Object>>` |

```java
STUFF = builder.defineCodec("general.stuff", CoolerCodecs.PRIMITIVE_MAP,
        Map.of("enabled", true, "scale", 1.5, "count", 10, "label", "hello"),
        "Mixed settings");
```

Because the declared value type is `Object`, numbers come back as `Number` — read them through
`Number`, never with a direct cast:

```java
Map<String, Object> m = STUFF.get();
boolean enabled = (Boolean) m.get("enabled");
int     count   = ((Number) m.get("count")).intValue();  // correct
// int  count   = (int) m.get("count");                  // ClassCastException on TOML
```

> If you know the value types ahead of time, prefer a record and `RecordCodecBuilder` — you get real
> `int` / `double` fields and no casting. `PRIMITIVE_MAP` is for genuinely dynamic keys.

### Numbers: `defineCodec` vs `defineMap`

`defineMap` stores raw values, so integers read back as `Long` from TOML/HOCON but as `Integer` from
JSON/JSON5 — a direct `(int)` cast can throw depending on the format.

`defineCodec` does not have this problem: a `Codec.INT` field always reads back as an `int`, whatever
wrote the file.

### HOCON caveat: map keys containing a dot

`ConfigFormat.HOCON` **cannot round-trip a map key containing a `.`**. HOCON reads an unquoted dot as
a path separator and Night-Config's HOCON writer does not quote such keys, so the entry re-parses as
a nested object, fails validation, and resets to its default. A `WARN` is logged when such a key is
written.

- Keys containing `:` — including resource locations like `minecraft:stone` — are **fine**.
- TOML, JSON, and JSON5 handle **every** key shape correctly.
- This affects map **keys** only — never record field names, nor the dotted `path` of the entry.

Use TOML, JSON, or JSON5 if your map keys may contain dots.

---

## Writing values back

`set` validates exactly as a file read does, and **returns whether the value was accepted**. A
rejected value leaves the entry untouched — it does not silently fall back to the default:

```java
if (!MyModConfig.MAX_ITEMS.set(userInput)) {
    showError("Value must be between 1 and 1000");
    return;
}
MyModConfig.CONFIG.save();   // persist to disk
```

Other write operations:

```java
MAX_ITEMS.reset();      // back to the declared default
CONFIG.reset();         // reset every entry in the spec
CONFIG.save();          // write current in-memory values to disk
CONFIG.reload();        // re-read from disk and fire reload listeners
```

`save()` is synchronous: when it returns, the bytes are on disk.

---

## Reload listeners

Register callbacks that fire after every `reload()`. Use them to recompute values derived from raw
entries, so derived state cannot drift out of sync with the file:

```java
public static int EFFECTIVE_RANGE;

CONFIG.addReloadListener(() -> {
    EFFECTIVE_RANGE = PARTICLES.get() ? RANGE.get() : 0;
});
```

Listeners fire on:

- `ConfigRegistry.reloadForServer()` — called automatically on server start (SERVER + COMMON configs)
- `ConfigRegistry.reloadForClient()` — called automatically once the client finishes initialising (CLIENT + COMMON configs)
- any manual `spec.reload()`
- a file change, if [hot-reload](#hot-reload-file-watching) is enabled

They do **not** fire after the initial load in `build()` — you are already in init code there.

A listener that throws is logged and skipped; it cannot prevent the other listeners from running.

| Event | What reloads |
|---|---|
| `ServerLifecycleEvents.SERVER_STARTING` (Fabric) / `ServerStartingEvent` (NeoForge) | `SERVER` + `COMMON` |
| `ClientLifecycleEvents.CLIENT_STARTED` (Fabric) / `FMLClientSetupEvent` (NeoForge) | `CLIENT` + `COMMON` |

---

## Hot-reload (file watching)

```java
ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
        .watchForChanges();
```

A daemon thread then watches the file and re-reads it whenever it is saved, firing all reload
listeners. It debounces for 150 ms, because most editors write in two steps. Being a daemon thread,
it stops when the game exits.

Reloads then happen **off the main thread**. Entry values are `volatile`, so any individual `get()`
is safe from anywhere — but if you need several entries to be consistent with each other, snapshot
them in a reload listener rather than reading them one by one.

---

## Building a config screen

`ConfigRegistry` and `ConfigSpec.entries()` let generic tooling enumerate configs it knows nothing
about:

```java
for (ConfigSpec spec : ConfigRegistry.getAll()) {
    for (ConfigEntry<?> entry : spec.entries()) {
        Class<?> type = entry.getType();

        if (type == Boolean.class)                    addToggle(entry);
        else if (Enum.class.isAssignableFrom(type))   addDropdown(entry, type.getEnumConstants());
        else                                          addTextField(entry);

        setTooltip(entry.getComment());     // the comment you wrote in define*
        setResetButton(entry::reset);       // back to entry.getDefaultValue()
    }
}
```

`entry.set(Object)` returns `false` for a value that fails validation, which is exactly what a screen
needs in order to show an inline error. Call `spec.save()` once when the player confirms.

`ConfigRegistry.find("mymod-common")` looks up a single spec by file name.

---

## Error handling

CoolerConfig is defensive about the file, because a config a player has hand-edited is a file that
can be broken:

- **Syntax error in the file** — the broken file is moved to `<name>.<ext>.bak`, an error is logged
  pointing at the backup, and the config is regenerated from defaults. **The game still starts.**
- **Invalid value** — reset to the default, logged at `WARN`, written back.
- **Missing key** (a field you added since the file was written) — filled in with its default.
- **Unknown key** (a field you removed) — pruned from the file.
- **Stale comments** — always rewritten from the spec.

Programming errors, in contrast, throw immediately at init so you catch them in dev:

- a default that fails its own validator, or a codec that cannot encode its default
- `min > max` on a ranged entry
- a duplicate key path within a spec
- two specs sharing one file name

---

## Complete example

```java
public final class MyModConfig {

    public static ConfigSpec COMMON;
    public static ConfigValue<Boolean> ENABLE;
    public static ConfigValue<Integer> COUNT;

    public static ConfigSpec CLIENT;
    public static ConfigValue<Boolean> PARTICLES;
    public static ConfigValue<Integer> RANGE;

    // derived — recomputed after every reload
    public static int EFFECTIVE_RANGE;

    public static void init() {
        ConfigBuilder common = ConfigSpec.builder("mymod", ConfigFormat.TOML)
                .comment("MyMod common configuration");
        ENABLE = common.defineBoolean("general.enable", true,       "Enable all MyMod features");
        COUNT  = common.defineInt    ("general.count",  10, 1, 100, "Spawn count per wave");
        COMMON = common.build();

        ConfigBuilder client = ConfigSpec.builder("mymod", ConfigFormat.TOML)
                .side(ConfigSide.CLIENT)
                .comment("MyMod client-only configuration")
                .watchForChanges();
        PARTICLES = client.defineBoolean("display.particles", true,      "Show particle effects");
        RANGE     = client.defineInt    ("display.range",     32, 1, 256, "Render range in blocks");
        CLIENT    = client.build();

        CLIENT.addReloadListener(() -> EFFECTIVE_RANGE = PARTICLES.get() ? RANGE.get() : 0);
        EFFECTIVE_RANGE = PARTICLES.get() ? RANGE.get() : 0;   // listeners do not fire on first load
    }
}
```

```java
// Fabric
public class MyMod implements ModInitializer {
    @Override public void onInitialize() {
        MyModConfig.init();
    }
}

// NeoForge
@Mod("mymod")
public class MyMod {
    public MyMod(IEventBus bus) {
        MyModConfig.init();
    }
}
```

This writes `config/mymod-common.toml` and `config/mymod-client.toml`, the latter hot-reloading
whenever it is saved.

---

## License

CC0-1.0 — see [LICENSE](LICENSE).
