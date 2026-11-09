# Glass tab shadow bounds

The full-width Mochi joined track previously reused the floating control recipe. In Maven 0.2.0 its 8dp offset, -6dp spread and 22dp softness produce an 80dp external outline reach. The three theme tracks' shadows overlapped and reached the content viewport boundary, producing a large rectangular dark area.

Only the joined track now copies the public light/dark material recipes with external shadow opacity zero. Native glass blur, tint, refraction, highlights and the segmented control's selection thumb are retained. Sheet and button materials are not globally changed.

The MIUIX native shadow is retained. Its normal eight-dp reach should fit the sheet's 24dp margin, and upstream draws the shadow before clipping the track shape. The actual local render nevertheless ended at the content's x=24/376 boundary. The theme editor now gives the scroll renderer the full sheet width and restores the same 24dp content inset inside it. Track position, width, selection and scrolling must remain unchanged.

The source alone does not establish which Android render layer clips: Compose 1.12.1 explicitly extends its scrolling/overscroll drawing bounds. Verification therefore compares full-root pixels, requires the native MIUIX shadow to paint beyond both track edges, and independently checks the original geometry, rather than deleting the shadow or asserting a speculative upstream bug.

Final focused verification passed: the six Mochi palette/background sampling matrix, exact save/cancel behavior, real MIUIX scroll/recovery, and the complete theme preview with left/right root-pixel shadow assertions. The final preview runner completed with exit 0 in 56 seconds; its track selector is scoped to the sheet, not the Cards page underneath. Baseline and after images are local Robolectric native graphics, not physical-device GPU evidence.
