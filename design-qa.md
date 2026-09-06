# Serein Android design QA

## Direction

Serein applies Apple-inspired product principles—clarity, content priority, restraint, consistency, generous spacing, and carefully reduced controls—using native Android and Compose behavior. It does not imitate iOS chrome.

The reader uses warm paper surfaces, deep green-black text, sage interactive states, a restrained apricot chapter accent, literary typography, soft 13–28 dp radii, and quiet hairlines. Reading controls remain visually secondary to the page.

## Verified states

All reference screenshots use a 390 × 844 dp viewport:

- Library: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/LibraryPreviewScreenshot_Serein library_384ba412_0.png`
- Reflowed reader: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/ReaderPreviewScreenshot_Serein reader_413cf6f4_0.png`
- Continuous reader: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/ScrollingReaderPreviewScreenshot_Serein scrolling reader_81f94ba9_0.png`
- Reading settings: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/ReaderSettingsPreviewScreenshot_Serein reader settings_466c6f84_0.png`
- Integrated dictionary: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/DictionaryPreviewScreenshot_Serein dictionary_3b8292a9_0.png`
- Dark reader: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/DarkReaderPreviewScreenshot_Serein dark reader_1b22f0ba_0.png`

No visible clipping, overlap, accidental truncation, or illegible contrast was found in these states. The small progress footer remains unobtrusive while exposing percentage read, pages remaining, and current/total page position.

## Launcher identity

Source visual truth: `/Users/a300074372/.codex/generated_images/01a06286-7a05-7850-84f7-cb01f28facde/exec-f0703e56-00fc-4a60-893e-425e49d01944.png`

Implementation: `app/src/main/res/mipmap-mdpi/ic_launcher.png`

Normalized comparison: `design/qa-logo-option3-comparison.png`

State: selected Reading Portal launcher mark on the standard ivory background.

The generated source is 1254 × 1254 pixels. The mdpi implementation is 48 × 48 pixels. For the focused comparison, the source was downsampled to 48 × 48 with the same high-quality raster pipeline, then both images were enlarged with nearest-neighbor sampling and placed side by side in a 960 × 480 comparison. The two normalized marks match pixel-for-pixel.

Full-view evidence: the nested portal silhouette, asymmetric sage inner layer, ivory aperture, apricot passage marker, outer margins, and warm background are preserved. All meaningful artwork remains inside safe crop margins.

Focused evidence: at 48 px, the two green layers remain distinct and the apricot marker remains visible. Edges are clean for a launcher raster with no transparency halo, compression artifact, or unintended color shift. No additional region crop was needed because the full normalized icon already exposes every critical detail.

Fidelity surfaces:

- Typography: the logo contains no text by design. The new library credit uses the app's existing 12 sp UI face and semibold sage emphasis for the linked author name.
- Spacing and layout: launcher negative space and adaptive crop clearance match the source. The credit is centered in a dedicated bottom footer and does not change the book grid hierarchy.
- Colors: ivory, forest ink, sage, and apricot map directly to the Serein palette.
- Image quality: the selected generated master is used directly; no code-drawn or placeholder substitute is present.
- Copy: the library displays `Made by rare-major`, and the entire credit opens the verified GitHub profile.

Findings: no actionable P0, P1, or P2 differences.

Comparison history: the selected option passed the first normalized comparison; no visual fixes were required after installation.

- Master: `design/serein-logo-master.png`
- Prompt: `design/serein-logo-prompt.md`
- Small-size proof: `app/src/main/res/mipmap-mdpi/ic_launcher.png`
- Library credit proof: `app/src/screenshotTestDebug/reference/com/serein/reader/ReaderPreviewScreenshotTestKt/LibraryPreviewScreenshot_Serein library_384ba412_0.png`

## Verification

- `testDebugUnitTest`: passed
- `lintDebug`: passed
- `validateDebugScreenshotTest`: passed for six UI states
- `assembleDebug`: passed

## Follow-up device checks

- Exercise TalkBack traversal and selection actions on a physical device.
- Check reflow/resume at the largest system font scale and in landscape.
- Confirm dictionary error handling on an intentionally offline device.

final result: passed
