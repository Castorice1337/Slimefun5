# Ancient Pedestal physical-component regression (fresh native Paper only)

`UpstreamPedestalProbe` is a standalone test plugin, outside normal production/test source sets.
Use a new localhost Paper26.1.2 world with Java25, Slimefun only and no actual player data.
Disable auto-update; accept the EULA yourself. After bootstrapping Paper dependencies and stopping it,
compile the probe against `libraries/**/*.jar`, `versions/**/*.jar`, and the supplied Slimefun JAR.
Package the class with this plugin.yml:
```yaml
name: ColumbinaItemTranslationProbe
version: '1.0'
main: columbina.slimefun.probe.UpstreamPedestalProbe
api-version: '26.1.2'
depend: [Slimefun]
```
Configure `server-ip=127.0.0.1` and a free high port. Start, then issue `stop` after
`ITEM_TRANSLATION_NATIVE_PASS`/`FAIL`; verify normal save/exit. Results are in
`plugins/ColumbinaItemTranslationProbe/result.json`. Never install this probe on production.

It invokes actual registered pedestal place/take routes with real item entities and native full-component
comparisons, not only display-text checks. Four immediate pairs per variant/material are tested:
raw-id/no-lore, Chinese/no-lore, Chinese/Resource lore, and custom lore plus foreign PDC/enchantment/model.
The localized and custom fixtures are deliberately supplied valid lore-bearing stacks; current fresh
raw-id SF5 templates do not contain lore. No claim is made that `/sf give` emits such stored lore by default.

Official development68:301 checks,72 failures (lore-bearing inputs lose lore). Inventory totals remain32;
hand falls to28 because returned items occupy another slot, not because any items were destroyed.
The one-line Consumer-overload fix passes301/301 and preserves every supplied metadata component.
This proves the native physical route, not actual client GUI or all historical name/italic edge cases.
