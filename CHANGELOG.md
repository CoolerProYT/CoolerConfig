# Changelog

## Unreleased — standalone release preparation

First release intended for use as a standalone library by mods other than CoolerProMC's own.
This cleans up the public API, so **there are breaking changes**.

### Breaking changes

| Before | Now | Why |
|---|---|---|
| `ConfigValue.set(T)` returned `void`, silently resetting the entry to its **default** when the value was invalid | returns `boolean`; a rejected value **leaves the entry unchanged** | Silently discarding a user's edit *and* the current value is the worst of both outcomes. A config screen needs to know the value bounced. |
| `ConfigSpec.set(String, Object)` returned `ConfigSpec` (chainable), same silent-reset behaviour | returns `boolean`, same semantics as above | Consistency with `ConfigValue.set`. Chains like `CONFIG.set(a, 1).set(b, 2).save()` must be split into separate statements. |
| `ConfigSpec.load()` was public | package-private — `build()` performs the initial load | Calling it a second time re-read the file and started a second file-watcher thread. It was never meant to be called by mods. |
| `ConfigSpec.getEntries()` (package-private, returned a `Map`) | `ConfigSpec.entries()` (public, returns `Collection<ConfigEntry<?>>`) | There was no supported way to enumerate a spec, despite the docs suggesting config screens should. |

### Fixed

- **Config file key order was randomised on every launch.** `ConfigBuilder.build()` copied the
  entries through `Map.copyOf`, whose iteration order is unspecified and, in practice, reshuffles
  on every JVM run. Since the file is rewritten whenever it disagrees with the spec, every player's
  config had its keys scrambled into a new order each time the game started. Entries now keep their
  declaration order.
- **A malformed config file crashed the game at startup.** A syntax error in a hand-edited file threw
  a `ParsingException` out of `build()` during mod init. The broken file is now moved aside to
  `<name>.<ext>.bak`, an error is logged pointing at the backup, and the config is regenerated from
  defaults.
- **`save()` could lose data.** Night-Config writes asynchronously by default, so `save()` returned
  before the bytes reached the disk: a `save()` followed by a `reload()` could read back the old
  contents, and a write issued shortly before the JVM exited could be lost. Writes are now
  synchronous.
- **`ConfigBuilder.comment(...)` did nothing for TOML and HOCON.** The file header was documented as
  supported for both, but Night-Config's TOML and HOCON writers only emit comments attached to a key
  that exists, so a comment set on the root path was silently dropped. (JSON5 was unaffected — that
  writer is ours.) The header is now written for TOML, HOCON, and JSON5.
- **Values written by the file-watcher thread were not guaranteed visible to game threads.**
  `ConfigEntry`'s value is now `volatile`, and the reload-listener list is a `CopyOnWriteArrayList`.
- **`defineEnum` rejected enums whose constants have bodies.** The validator compared `getClass()`,
  which for a constant with a body is an anonymous subclass, so no value ever matched. It now
  checks against the declaring class.
- A reload listener that threw prevented every subsequent listener from running. Listener exceptions
  are now logged and skipped.
- `Services.load` threw `NullPointerException` when a platform service was missing; it now throws
  `IllegalStateException`.

### Added

- `defineFloat(path, default, min, max, comment)`.
- `defineList(path, default, comment, elementValidator)` — validates every element, so a bad entry
  in a player-edited list is caught at load rather than as a `ClassCastException` somewhere else.
- `ConfigValue`: `reset()`, `getPath()`, `getComment()`, `getEntry()`.
- `ConfigSpec`: `entries()`, `entry(path)`, `reset()`, `getFilePath()`, `getFloat(path)`,
  `removeReloadListener(Runnable)`.
- `ConfigEntry`: `getType()` and `reset()`, so config screens can pick a widget per entry.
- `ConfigRegistry.find(name)` — look up a spec by file name.
- `ConfigFormat.getExtension()`.
- `ConfigBuilder.fileName()` now accepts `/` to write into a subdirectory of the config folder.

### Validation of programming errors

These now throw at init instead of producing a subtly broken config:

- a default value that fails its own validator (e.g. `defineInt("x", 999, 1, 100, ...)`), or a codec
  that cannot encode its own default
- `min > max` on a ranged entry
- a blank key path, or one with an empty segment (`a..b`, `.a`, `a.`)
- two specs registered under the same file name — they would have overwritten each other's keys on
  every save
- calling `build()` twice on one builder

### Removed

- The example mixin inherited from MultiLoader-Template, which injected into `TitleScreen` and
  logged *"This line is printed by an example mod mixin from NeoForge!"* on every title screen. All
  three mixin config files and the mixin/mixinextras build dependencies are gone with it —
  CoolerConfig patches nothing.
- NeoForge datagen run configuration and gametest namespace — a config library has no data to generate.

### Changed

- Fabric now depends on `fabric-lifecycle-events-v1` rather than the whole of `fabric-api`.
- `fabric.mod.json` and `neoforge.mods.toml` no longer carry MultiLoader-Template placeholders
  (`fabric-example-mod` as the source URL, a suggested `another-mod`, `change.me.to.your...` URLs),
  and no longer reference a `coolerconfig.png` icon that does not exist in the repository.
- The NeoForge dependency range is `[26.1,)` rather than pinned to the exact beta build compiled
  against.
- The published POM now carries name, description, URL, license, developer, and SCM metadata. The
  remote Maven repository is only declared when `maven_url` is set, so building and publishing to
  `mavenLocal` works without credentials configured.
- Log messages no longer prefix `[CoolerConfig]` — the logger name already carries it.
