# Feature extraction: PdfCraft → Mobile PDF Editor

Source: `storytold/pdfcraft` (MIT OR Apache-2.0). It is a ~150k-line Rust desktop/web app that tracks
860 Acrobat-parity features (`parity/acrobat-features.toml`), ~400 shipped. Its crates map to 14 areas
(A core … N misc). Most of it is desktop-class (JavaScript engine, XFA, PDF/A, PRC 3D, MCP server,
Acrobat-style tabs/menus/shortcuts) and is **not** appropriate for a low-end phone.

We re-implement PdfCraft's ideas in Kotlin (it is MIT OR Apache-2.0; the owner allowed porting its
algorithms). Redaction and Remove Hidden Information follow `crates/redact` closely.

## Triage rules for low-end Android (1–2 GB RAM, ARM, small screen, touch only)

1. Must work on a 1 GB / Android 7 device with a page rendered one at a time.
2. Prefer features that map to a thumb gesture over those that need menus, keyboard or hover.
3. Anything that needs a heavy dependency (OCR models, JS interpreter, ICC colour) is optional/later.
4. Everything is offline; no account, no telemetry (same promise as PdfCraft).

## Legend
**MVP** = v1.0 · **v2** = after launch · **Skip** = deliberately not on mobile

---

## A. Core — open, save, safety
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Open PDF, tolerate damaged files | **MVP** | Try renderer, fall back to PdfBox repair parse; show clear error |
| Password-protected open (RC4/AES) | **MVP** | Password dialog with retry |
| Honour document permissions | **MVP** | Disable edit tools with an explanation |
| Undo / redo with named steps | **MVP** | In-memory command stack, "Undo: Rotate page" |
| Autosave + crash recovery | **MVP** | Draft copy in app cache every edit-batch; offer restore |
| Atomic save (temp file then swap) | **MVP** | Never corrupt the user's original |
| Save / Save a copy / Share | **MVP** | SAF (Storage Access Framework) — no storage permission needed |
| Incremental save (original bytes kept) | v2 | PdfBox supports `saveIncremental` |
| Unsaved-changes prompt | **MVP** | |
| Recent files | **MVP** | Persisted URIs + cached thumbnail |
| Revisions / compare revisions | Skip | |
| Document properties (title, author, size, pages, encryption) | **MVP** | Bottom sheet; title/author/subject editable |

## B. View / read
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Fast, crash-isolated page rendering | **MVP** | Render one page at a time, bitmap cache sized to device RAM |
| Tiled deep zoom | **MVP** | Re-render visible region at zoom level (no giant bitmaps) |
| Continuous scroll / single page layout | **MVP** | Vertical scroll + horizontal page-swipe toggle |
| Two-up layout | v2 | Landscape tablets only |
| Pinch zoom, double-tap zoom, fit width/page | **MVP** | |
| Rotate view (non-destructive) | **MVP** | |
| Read mode (hide chrome), full screen | **MVP** | Tap to toggle chrome |
| Go to page, page slider, page labels | **MVP** | Fast scrubber |
| Find text (next/prev, match case, whole word) | **MVP** | Per-page lazy extraction so it does not freeze |
| Select / copy text | v2 | Long-press selection needs text positions |
| Thumbnails panel | **MVP** | Low-res, lazy, LRU cached |
| Bookmarks (outline) panel, search in it | **MVP** | |
| Follow links / URLs | **MVP** | |
| Attachments panel | v2 | |
| Layers panel | Skip | |
| Dark theme | **MVP** | Plus *inverted page* reading mode (night) |
| Tabs, marquee zoom, hand tool, view history | Skip | Desktop idioms |

## C. Edit
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Add text box | **MVP** | Tap → type; font size, colour, bold/italic |
| Add image / stamp / signature image | **MVP** | Pick from gallery or camera |
| Move / resize / delete added objects | **MVP** | Handles sized for fingers (48dp) |
| Watermark (text/image, opacity, rotation) | **MVP** | |
| Header/footer, page numbers (Bates-style) | **MVP** (page numbers) / v2 (full) | |
| Page background colour | v2 | |
| Crop pages | **MVP** | Drag handles on thumbnail |
| Edit *existing* text in place, reflow | v2 | Hardest feature; PdfCraft itself is partial. Use "cover & retype" first |
| Links: create web/page links | v2 | |
| Cut/copy/paste objects, arrange z-order | v2 | |

## D. Organize pages
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Page grid with multi-select | **MVP** | Long-press to enter select mode |
| Rotate, delete, duplicate pages | **MVP** | |
| Reorder (drag & drop) | **MVP** | |
| Insert blank page / from another file | **MVP** | |
| Extract pages to new PDF | **MVP** | Range + visual select |
| Split every N / by ranges | **MVP** | |
| Merge / combine files, choose pages per file | **MVP** | Merge builds bookmark per file |
| Select odd/even/range | **MVP** | |
| Edit bookmarks | v2 | |
| Page labels, page boxes | v2 | |
| Replace pages, split by size/bookmarks | v2 | |

## E. Comments & markup
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Highlight / underline / strikethrough text | **MVP** | Select by finger-drag over words |
| Freehand ink (pen), eraser, highlighter pen | **MVP** | Pressure-free; smoothing |
| Sticky note | **MVP** | |
| Shapes: line, arrow, rectangle, oval | **MVP** | |
| Polygon, cloud, callout, polyline | v2 | |
| Colour, thickness, opacity per tool, remembered | **MVP** | |
| Comments list (jump, delete, edit) | **MVP** | |
| Threads/replies, review status | Skip | Collaboration is out of scope |
| Export/import XFDF | Skip | |

## F. Forms
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Fill text, checkbox, radio, dropdown, list | **MVP** | Native Android inputs, not tiny desktop widgets |
| Highlight fields; "next field" button | **MVP** | Keyboard IME "Next" jumps to next field |
| Flatten form | **MVP** | |
| Reset form | **MVP** | |
| Formatting/calculation (AFNumber, AFDate…) | v2 | Only the common built-ins, no JS engine |
| Fill & Sign: text, ✓, ✗, date, initials, signature | **MVP** | Signature pad, saved signatures |
| Create form fields (prepare form) | v2 | |
| Import/export form data (FDF/CSV) | Skip | |
| JavaScript, XFA | Skip | Heavy, security surface |

## G. Protect
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Set open password (AES-256) | **MVP** | |
| Permissions password (restrict print/copy/edit) | **MVP** | |
| Remove password | **MVP** | |
| Redaction (mark, apply, verify) | v2 | Real redaction must strip content, not draw boxes — do it properly or not at all |
| Sanitize / remove metadata | **MVP** (metadata) / v2 (full) | |
| Digital signatures (X.509/PAdES) | v2 | Visible handwritten signature is MVP |

## H–I. Scan & OCR
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Scan with camera → PDF | **MVP** | CameraX, edge detect optional, multi-page |
| Image enhance (contrast, B/W, deskew) | **MVP** (B/W/contrast) / v2 (auto-deskew) | |
| OCR → searchable PDF | v2 | Optional on-device model download so base APK stays small |

## J. Create / convert / export
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Images → PDF (multi-image, page size, margins) | **MVP** | Downscale to avoid OOM |
| Blank PDF, text → PDF | **MVP** (blank) | |
| PDF → images (PNG/JPG), per page | **MVP** | |
| Extract text | **MVP** | Share as .txt |
| Extract embedded images | v2 | |
| PDF → Word/RTF/HTML/PPTX | Skip | Needs layout engine |

## K. Optimize
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Reduce file size (low / medium / high) | **MVP** | Recompress/downsample images, drop unused objects, object streams; shows before/after size |
| Audit space usage | v2 | |
| PDF/A | Skip | |

## L–N. Print, accessibility, misc
| PdfCraft feature | Mobile | Notes |
|---|---|---|
| Print | **MVP** | Android PrintManager |
| Share / Open with / "Open in" intent handler | **MVP** | App registers as PDF viewer |
| Compare files | v2 | |
| Batch / actions / CLI / MCP | Skip | |
| Accessibility Checker (32 rules) | Skip | But app UI itself must be TalkBack-friendly |
| Localization (Fluent) | **MVP** | Android string resources; ship English + Urdu/Arabic RTL-ready |
| Preferences (theme, default layout, autosave) | **MVP** | |
| Command palette, shortcuts | Skip | Replaced by tool grid + search on home |

---

## Implementation status (2026-11-27, v0.9.0)

Added: Urdu interface (RTL), search progress, fit text width, faster opening on 1 GB phones. Localization
row above: English + Urdu done. 14 JVM + 50 device tests on Android 7.

## Implementation status (2026-11-27, v0.8.0)

Added: paragraph editing (re-wrap, keeps justification), compatible renderer for Android 7 (Type 1 fonts) with
a PdfBox-Android glyph-cache fix, read aloud, tick/cross/dot marks. 13 JVM + 49 device tests on Android 7;
real-world files (arXiv paper, IRS W-9) pass edit/redact/forms/compare/sanitize/sign/paragraph tests.

## Implementation status (2026-11-27, v0.6.0)

Added: digital signatures (sign + verify, no BouncyCastle: own DER/CMS), cloud and area-highlight shapes,
insert pages from another PDF, Settings/About with licences. Tested on real files (arXiv paper, IRS W-9):
edit, redact, forms, compare, sanitize, sign. 13 JVM + 46 on-device tests on the A15.

## Implementation status (2026-11-27, v0.5.0)

Added: edit bookmarks, form calculations (AFSimple_Calculate, simplified field notation, AFNumber/
AFPercent formats, calculation order), compare (text, per page, report PDF), stamps. 11 JVM + 38
on-device tests pass on the A15. Still open: two-up layout, auto-deskew, localization, digital
signatures, polygon/cloud/callout shapes.

## Implementation status (2026-11-25, v0.4.0)

Tested on the target phone (Samsung Galaxy A15, Android 16): 9 JVM + 33 on-device tests.
Added since v0.3: **redaction** (mark words/areas, find & mark words and patterns, apply removes
content for good with verification), **text select & copy** (long-press, handles, copy/share/highlight/
redact), **sticky notes** (real PDF comments) and a **Comments & changes** list, **insert picture**,
**remove hidden information** (8 categories), **extract images**, **attachments** (list, save, attach),
**header & footer**. Still open: two-up layout, form calculations, bookmark editing, auto-deskew,
localization, digital signatures, compare.

## Implementation status (2026-11-23)

Built and tested on an Android 7 / 1 GB emulator: everything in the MVP summary below **except**
two-up layout, text select/copy, attachments, form field calculations, sanitize, auto-deskew, and
localization. Added beyond the original MVP list: crop pages, read-only mode for restricted PDFs,
display of annotation appearances (the OS renderer skips them).

## MVP summary (what v1.0 contains)
**Read** (render, zoom, search, bookmarks, thumbnails, night mode) ·
**Annotate** (highlight, underline, strike, pen, highlighter, eraser, text, shapes, note) ·
**Fill & Sign** (forms, signature, date, check) ·
**Organize** (reorder, rotate, delete, insert, duplicate, extract, split, merge) ·
**Create** (images → PDF, camera scan → PDF) ·
**Export** (PDF → images, text) ·
**Protect** (set/remove password, permissions) ·
**Tools** (compress, watermark, page numbers, crop, metadata) ·
**System** (open-with, share, print, recent files, autosave/recovery, dark mode).
