# Native translation-prediction regression (isolated Paper only)

`UpstreamStackingProbe` is not part of the production JAR or normal Gradle test source set.
Run it only on a new localhost Paper world with Slimefun and this test plugin, no addons or player data.
It creates synthetic native players and invokes real byte codecs, the native container click handler,
and 64 immediate pedestal place/take pairs per material. It does not claim real-client GUI acceptance.

Tested with Java 25, Paper 26.1.2-74 and 26.2-129. Use `api-version: '26.1.2'` for the probe on either.
Configure the fresh server with `server-ip=127.0.0.1`, a free high port, `online-mode=false`,
a flat world, and view/simulation distance 2. Accept the Paper EULA yourself and bootstrap dependencies.
Disable Slimefun auto-update; set `translation.packets: true` (or false for the untranslated control).

Example PowerShell compilation after bootstrapping the empty Paper server and stopping it:
```powershell
$cp = ((Get-ChildItem ./libraries,./versions,./plugins -Recurse -Filter *.jar).FullName -join ';')
javac -encoding UTF-8 -cp $cp -d ./probe-classes /path/to/UpstreamStackingProbe.java
@"
name: ColumbinaItemTranslationProbe
version: '1.0'
main: columbina.slimefun.probe.UpstreamStackingProbe
api-version: '26.1.2'
depend: [Slimefun]
"@ | Set-Content ./probe-classes/plugin.yml -Encoding utf8
jar --create --file ./plugins/PacketProbe.jar -C ./probe-classes .
java -Xms256M -Xmx2G -jar paper.jar --nogui
```
After `ITEM_TRANSLATION_NATIVE_PASS`/`FAIL`, issue `stop` and verify normal save/exit.
Results are in `plugins/ColumbinaItemTranslationProbe/result.json`. Remove this test plugin after the run.

Official development #68 (SHA 72b7d8ade56ffa6dee98908a5a5e00927df020bc0eb727bd8ad420f467d6f572)
fails 12 of 853 assertions with translation enabled, while its pedestal metadata/amount checks pass.
The narrow patch passes 853/853 on Paper 26.1.2 with translation still enabled.
Both English and Chinese canonical material pairs are covered, plus vanilla stone.
No physical duplicate was observed: the regression checks a stale nonempty cursor correction.
