# CoolerConfig

A multiloader (Fabric + NeoForge) configuration library for Minecraft 26.1, built on top of [Night-Config](https://github.com/TheElectronWill/night-config). Supports **TOML**, **HOCON**, **JSON**, and **JSON5** formats with side-aware loading, comment support, validation, and lifecycle-event-driven reloading.

---

## Adding CoolerConfig as a dependency

CoolerConfig is published to the CoolerProMC Maven repository. Players must have CoolerConfig installed as a separate mod — it is **not** a shadeable utility, so do not `include` / `jarJar` it.

### 1 — Add the Maven repository

Add this block to **every** subproject that needs CoolerConfig (or to a shared `repositories` block in your root `build.gradle` / convention plugin):

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
    // Night-Config core + toml are provided by NeoForge; hocon is JiJ'd by CoolerConfig.
    compileOnly "com.coolerpromc.coolerconfig:coolerconfig-common-26.1.2:26.1.2.0"
}
```

**Fabric** (`fabric/build.gradle`):
```groovy
dependencies {
    // Night-Config is already bundled inside the CoolerConfig jar — no extra deps needed.
    implementation include("com.coolerpromc.coolerconfig:coolerconfig-fabric-26.1.2:26.1.2.0")
}
```

**NeoForge** (`neoforge/build.gradle`):
```groovy
dependencies {
    // Night-Config core + toml are provided by NeoForge; hocon is JiJ'd by CoolerConfig.
    jarJar implementation("com.coolerpromc.coolerconfig:coolerconfig-neoforge-26.1.2:26.1.2.0")
}
```

> Replace `26.1.2.0` with the actual release version you want to target.
> Check the repository at `https://maven.coolerpromc.com/releases/com/coolerpromc/coolerconfig/`
> for a list of available versions.

### 3 — Declare the mod dependency

Tell the loader that CoolerConfig must be present and initialised **before** your mod. This prevents your config from being built before CoolerConfig's platform services are ready.

**`fabric.mod.json`:**
```json
"depends": {
    "coolerconfig": ">=26.1.2.0"
}
```

**`META-INF/neoforge.mods.toml`** — add a new `[[dependencies.<your_mod_id>]]` block:
```toml
[[dependencies.<your_mod_id>]]
    modId    = "coolerconfig"
    type     = "required"
    versionRange = "[26.1.2.0,)"
    ordering = "AFTER"
    side     = "BOTH"
```

The `ordering = "AFTER"` line ensures CoolerConfig's `@Mod` constructor (which registers the platform services) runs before yours.

### 4 — Import the API

```java
import com.coolerpromc.coolerconfig.config.ConfigSpec;
import com.coolerpromc.coolerconfig.config.ConfigBuilder;
import com.coolerpromc.coolerconfig.config.ConfigValue;
import com.coolerpromc.coolerconfig.config.ConfigFormat;
import com.coolerpromc.coolerconfig.config.ConfigSide;
// only if you need manual bulk-reload:
import com.coolerpromc.coolerconfig.config.ConfigRegistry;
```

---

## Quick start

Call `ConfigSpec.builder(...)` during your mod's **init phase** (inside `onInitialize` / your `@Mod` constructor). Do **not** call it in a `static {}` block at class-load time — the config directory is not available yet.

Each `define*` method returns a typed `ConfigValue<T>` handle. Store these as static fields and use them to read and write values — no string paths, no risk of typos.

```java
public class MyModConfig {

    // Declare handles as static fields
    public static ConfigValue<Boolean>    ENABLE_FEATURE;
    public static ConfigValue<Integer>    MAX_ITEMS;
    public static ConfigValue<Long>       SEED;
    public static ConfigValue<Double>     SCALE;
    public static ConfigValue<String>     MESSAGE;
    public static ConfigValue<Difficulty> DIFFICULTY;
    public static ConfigValue<List<String>> BLACKLIST;

    public static ConfigSpec CONFIG;

    public static void init() {
        ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
                .side(ConfigSide.COMMON)         // optional — COMMON is the default
                .comment("My mod configuration"); // TOML file header comment

        ENABLE_FEATURE = builder.defineBoolean("general.enableFeature", true,   "Enable the main feature");
        MAX_ITEMS      = builder.defineInt    ("general.maxItems",    64, 1, 1000, "Maximum item count");
        SEED           = builder.defineLong   ("general.seed",        0L, Long.MIN_VALUE, Long.MAX_VALUE, "World seed");
        SCALE          = builder.defineDouble ("general.scale",       1.5, 0.1, 10.0, "Scale multiplier");
        MESSAGE        = builder.defineString ("general.message",     "Hello!", "Chat message");
        DIFFICULTY     = builder.defineEnum   ("general.difficulty",  Difficulty.NORMAL, "Difficulty level");
        BLACKLIST      = builder.defineList   ("general.blacklist",   List.of("minecraft:dirt"), "Blacklisted items");

        CONFIG = builder.build(); // creates file if absent, loads values, registers with ConfigRegistry
    }
}
```

Call `MyModConfig.init()` from your mod initialiser (see [Complete example](#complete-example)).

Read values anywhere after init:

```java
boolean      on   = MyModConfig.ENABLE_FEATURE.get();
int          max  = MyModConfig.MAX_ITEMS.get();
long         seed = MyModConfig.SEED.get();
double       s    = MyModConfig.SCALE.get();
String       msg  = MyModConfig.MESSAGE.get();
Difficulty   diff = MyModConfig.DIFFICULTY.get();
List<String> bl   = MyModConfig.BLACKLIST.get();
```

---

## Config formats

| Constant | File extension | Comments written | Comment syntax |
|---|---|---|---|
| `ConfigFormat.TOML` | `.toml` | Yes — per-entry and header | `# comment` |
| `ConfigFormat.HOCON` | `.conf` | Yes — per-entry and header | `# comment` |
| `ConfigFormat.JSON` | `.json` | No | N/A |
| `ConfigFormat.JSON5` | `.json5` | Yes — per-entry and header | `// comment` |

> **JSON5:** [JSON5](https://json5.org/) is a strict superset of JSON. Unlike plain `JSON`, the
> `JSON5` format writes comments. Files are emitted as pretty-printed, still-valid JSON; on read,
> the full JSON5 syntax (comments, trailing commas, single quotes, unquoted keys, hex numbers, …)
> is accepted, so hand-edited files using those features load correctly.

> **Note:** CoolerConfig rewrites the entire file on every save/correction. Hand-written comments
> already in the file are not preserved across a rewrite.

---

## Config sides

Control which physical side loads a config with `.side(ConfigSide.X)`:

| Value | Loads on | Typical use |
|---|---|---|
| `ConfigSide.COMMON` | Client **and** server | Most configs — the default |
| `ConfigSide.SERVER` | Client **and** dedicated server | Game-balance settings (server-authoritative) |
| `ConfigSide.CLIENT` | Physical client only | HUD, keybinds, visual settings |

`CLIENT` configs are silently skipped on a dedicated server; their entries keep their default values.

```java
// A visual-only config — never loads on a headless server
public static ConfigValue<Boolean> SHOW_TIMER;
public static ConfigValue<Integer> HUD_X;
public static ConfigSpec HUD_CONFIG;

// A server-balance config
public static ConfigValue<Double> DAMAGE_MULTIPLIER;
public static ConfigSpec SERVER_CONFIG;

public static void init() {
    ConfigBuilder hudBuilder = ConfigSpec.builder("mymod-client", ConfigFormat.TOML)
            .side(ConfigSide.CLIENT);
    SHOW_TIMER = hudBuilder.defineBoolean("hud.showTimer", true, "Show the countdown timer");
    HUD_X      = hudBuilder.defineInt    ("hud.x", 10, 0, 1920, "HUD X position");
    HUD_CONFIG = hudBuilder.build();

    ConfigBuilder serverBuilder = ConfigSpec.builder("mymod-server", ConfigFormat.TOML)
            .side(ConfigSide.SERVER);
    DAMAGE_MULTIPLIER = serverBuilder.defineDouble("balance.damageMultiplier", 1.0, 0.1, 5.0, "Damage multiplier");
    SERVER_CONFIG     = serverBuilder.build();
}
```

---

## Hot-reload (file watching)

Call `.watchForChanges()` on the builder to have the config automatically re-read whenever the file is saved on disk:

```java
ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
        .watchForChanges();  // daemon thread watches the file; calls reload() on every save
ENABLE = builder.defineBoolean("general.enable", true, "Enable features");
CONFIG = builder.build();
```

After `build()`, a daemon `WatchService` thread starts for the config directory. When the file is saved, the thread waits 150 ms (debounce — most editors write in two steps) then calls `reload()`, which re-reads values from disk and fires all registered reload listeners.

The watcher thread is a daemon — it stops automatically when the JVM exits.

---

## Reload listeners

Register callbacks that fire after every `reload()`. Use this to recompute values that are derived from raw config entries.

```java
public static double CACHED_SCALE;

static {
    CONFIG.addReloadListener(() -> {
        CACHED_SCALE = SCALE.get() * 2.0;
    });
}
```

Listeners fire on:
- `ConfigRegistry.reloadForServer()` — triggered automatically by CoolerConfig on server start (SERVER + COMMON configs)
- `ConfigRegistry.reloadForClient()` — triggered automatically by CoolerConfig when the client finishes initialising (CLIENT + COMMON configs)
- Manual calls to `spec.reload()`

---

## Automatic lifecycle reloading

CoolerConfig registers the following events internally so you do not have to:

| Event | What reloads |
|---|---|
| `ServerLifecycleEvents.SERVER_STARTING` (Fabric) / `ServerStartingEvent` (NeoForge) | `SERVER` + `COMMON` configs |
| `ClientLifecycleEvents.CLIENT_STARTED` (Fabric) / `FMLClientSetupEvent` (NeoForge) | `CLIENT` + `COMMON` configs |

This means if a player edits a config file while the game is running and then restarts/reloads the server, values are re-read from disk automatically.

---

## In-game config screen

`ConfigValue.set(T)` updates the value in memory. Call `CONFIG.save()` afterwards to persist it to disk. Values go through the same coercion and validation as file reads — out-of-range values silently fall back to their default.

```java
// apply changes from a config screen
MyModConfig.MAX_ITEMS.set(32);
MyModConfig.ENABLE_FEATURE.set(false);
MyModConfig.CONFIG.save();
```

For generic screens that iterate all entries without knowing their types ahead of time, `ConfigSpec.set(String path, Object value)` is also available:

```java
CONFIG.set("general.maxItems", 32).set("general.enableFeature", false).save();
```

---

## Manual reload / save

```java
CONFIG.reload(); // re-read from disk, fire listeners, write back if values were corrected
CONFIG.save();   // write current in-memory values to disk (overwrites defaults)
```

You can also trigger a full reload of all registered configs:

```java
ConfigRegistry.reloadForServer(); // reloads SERVER + COMMON
ConfigRegistry.reloadForClient(); // reloads CLIENT + COMMON
ConfigRegistry.getAll();          // returns unmodifiable list of all ConfigSpec instances
```

---

## Validation

Invalid values in the config file are silently reset to their defaults and the file is rewritten. A `WARN` log line is emitted:

```
[CoolerConfig] 'general.maxItems': invalid value '9999', resetting to default '64'
```

Built-in validators:

| Method | Validator |
|---|---|
| `defineBoolean(path, default, comment)` | must be a Boolean |
| `defineInt(path, default, min, max, comment)` | `min <= value <= max` |
| `defineLong(path, default, min, max, comment)` | `min <= value <= max` |
| `defineDouble(path, default, min, max, comment)` | `min <= value <= max` |
| `defineString(...)` | must be a String |
| `defineEnum(path, default, comment)` | name must match a constant of the enum type |
| `defineList(...)` | must be a List |
| `defineMap(path, default, comment)` | must be a Map |
| `define(path, default, comment, validator)` | custom `Predicate<Object>` |

```java
// Custom validator — only allow specific string values
.defineString("mode.type", "normal", "Game mode (normal/hard/extreme)",
        v -> v instanceof String s && Set.of("normal", "hard", "extreme").contains(s))
```

---

## Complete example

```java
public class MyModConfig {

    // Common config handles
    public static ConfigValue<Boolean> ENABLE;
    public static ConfigValue<Integer> COUNT;
    public static ConfigSpec COMMON;

    // Client config handles
    public static ConfigValue<Boolean> PARTICLES;
    public static ConfigValue<Integer> RANGE;
    public static ConfigSpec CLIENT;

    // Derived value — recomputed after every reload
    public static int EFFECTIVE_RANGE;

    public static void init() {
        ConfigBuilder commonBuilder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
                .comment("MyMod common configuration");
        ENABLE = commonBuilder.defineBoolean("general.enable", true,  "Enable all MyMod features");
        COUNT  = commonBuilder.defineInt    ("general.count",  10, 1, 100, "Spawn count per wave");
        COMMON = commonBuilder.build();

        ConfigBuilder clientBuilder = ConfigSpec.builder("mymod-client", ConfigFormat.TOML)
                .side(ConfigSide.CLIENT)
                .comment("MyMod client-only configuration");
        PARTICLES = clientBuilder.defineBoolean("display.particles", true, "Show particle effects");
        RANGE     = clientBuilder.defineInt    ("display.range",     32, 1, 256, "Render range in blocks");
        CLIENT    = clientBuilder.build();

        CLIENT.addReloadListener(() -> {
            EFFECTIVE_RANGE = PARTICLES.get() ? RANGE.get() : 0;
        });
    }
}
```

In your mod initialiser:

```java
// Fabric
@Override
public void onInitialize() {
    MyModConfig.init();
}

// NeoForge
@Mod("mymod")
public class MyMod {
    public MyMod(IEventBus bus) {
        MyModConfig.init();
    }
}
```

---

## File locations

Config files are written to the standard loader config directory. The file name is derived
automatically from the mod ID passed to `ConfigSpec.builder()` plus the config side:

```
.minecraft/config/<modId>-<side>.toml    (TOML)
.minecraft/config/<modId>-<side>.conf    (HOCON)
.minecraft/config/<modId>-<side>.json    (JSON)
.minecraft/config/<modId>-<side>.json5   (JSON5)
```

Examples:

| Builder call | File created |
|---|---|
| `builder("mymod", TOML)` (COMMON default) | `mymod-common.toml` |
| `builder("mymod", TOML).side(CLIENT)` | `mymod-client.toml` |
| `builder("mymod", HOCON).side(SERVER)` | `mymod-server.conf` |

The directory is created automatically on first run.
