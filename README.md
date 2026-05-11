# CoolerConfig

A multiloader (Fabric + NeoForge) configuration library for Minecraft 26.1, built on top of [Night-Config](https://github.com/TheElectronWill/night-config). Supports **TOML** and **HOCON** formats with side-aware loading, comment support, validation, and lifecycle-event-driven reloading.

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
import com.coolerpromc.coolerconfig.config.ConfigFormat;
import com.coolerpromc.coolerconfig.config.ConfigSide;
// only if you need manual bulk-reload:
import com.coolerpromc.coolerconfig.config.ConfigRegistry;
```

---

## Quick start

Call `ConfigSpec.builder(...)` during your mod's **init phase** (inside `onInitialize` / your `@Mod` constructor). Do **not** call it in a `static {}` block at class-load time — the config directory is not available yet.

```java
public static final ConfigSpec CONFIG = ConfigSpec.builder("mymod", ConfigFormat.TOML)
        .side(ConfigSide.COMMON)           // optional — COMMON is the default
        .comment("My mod configuration")   // TOML file header comment
        .define("general.enableFeature", true,   "Enable the main feature")
        .defineInt("general.maxItems",    64, 1, 1000, "Maximum item count")
        .defineDouble("general.scale",    1.5, 0.1, 10.0, "Scale multiplier")
        .defineString("general.message",  "Hello!", "Chat message")
        .defineList("general.blacklist",  List.of("minecraft:dirt"), "Blacklisted items")
        .build();  // creates file if absent, loads values, registers with ConfigRegistry
```

Read values anywhere after init:

```java
boolean on  = CONFIG.getBoolean("general.enableFeature");
int     max = CONFIG.getInt("general.maxItems");
double  s   = CONFIG.getDouble("general.scale");
String  msg = CONFIG.getString("general.message");
List<String> bl = CONFIG.getList("general.blacklist");
```

---

## Config formats

| Constant | File extension | Comments written |
|---|---|---|
| `ConfigFormat.TOML` | `.toml` | Yes — per-entry and header |
| `ConfigFormat.HOCON` | `.conf` | No (HOCON comments are readable but not written programmatically) |

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
public static final ConfigSpec HUD_CONFIG = ConfigSpec.builder("mymod-client", ConfigFormat.TOML)
        .side(ConfigSide.CLIENT)
        .define("hud.showTimer", true, "Show the countdown timer")
        .defineInt("hud.x", 10, 0, 1920, "HUD X position")
        .build();

// A server-balance config
public static final ConfigSpec SERVER_CONFIG = ConfigSpec.builder("mymod-server", ConfigFormat.TOML)
        .side(ConfigSide.SERVER)
        .defineDouble("balance.damageMultiplier", 1.0, 0.1, 5.0, "Damage multiplier")
        .build();
```

---

## Reload listeners

Register callbacks that fire after every `reload()`. Use this to recompute values that are derived from raw config entries.

```java
public static double CACHED_SCALE;

static {
    CONFIG.addReloadListener(() -> {
        CACHED_SCALE = CONFIG.getDouble("general.scale") * 2.0;
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
| `defineInt(path, default, min, max, comment)` | `min <= value <= max` |
| `defineDouble(path, default, min, max, comment)` | `min <= value <= max` |
| `defineString(...)` | must be a String |
| `defineList(...)` | must be a List |
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

    public static final ConfigSpec COMMON = ConfigSpec.builder("mymod", ConfigFormat.TOML)
            .comment("MyMod common configuration")
            .define("general.enable",   true,   "Enable all MyMod features")
            .defineInt("general.count", 10, 1, 100, "Spawn count per wave")
            .build();

    public static final ConfigSpec CLIENT = ConfigSpec.builder("mymod-client", ConfigFormat.TOML)
            .side(ConfigSide.CLIENT)
            .comment("MyMod client-only configuration")
            .define("display.particles", true, "Show particle effects")
            .defineInt("display.range",  32, 1, 256, "Render range in blocks")
            .build();

    // Derived value — recomputed after every reload
    public static int EFFECTIVE_RANGE;

    static {
        CLIENT.addReloadListener(() -> {
            EFFECTIVE_RANGE = CLIENT.getBoolean("display.particles")
                    ? CLIENT.getInt("display.range")
                    : 0;
        });
    }
}
```

In your mod initialiser:

```java
// Fabric
@Override
public void onInitialize() {
    MyModConfig.COMMON; // triggers static initialiser → build() → load()
    MyModConfig.CLIENT;
}

// NeoForge
@Mod("mymod")
public class MyMod {
    public MyMod(IEventBus bus) {
        MyModConfig.COMMON;
        MyModConfig.CLIENT;
    }
}
```

---

## File locations

Config files are written to the standard loader config directory:

| Loader | Path |
|---|---|
| Fabric | `.minecraft/config/<name>.toml` |
| NeoForge | `.minecraft/config/<name>.toml` |

The directory is created automatically on first run.
