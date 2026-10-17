package hk.uwu.reareye.agent.htmlwidget

/** English widget-generation contract. Runtime identifiers and formulas are intentionally stable. */
object WidgetSpec {
    /** Frame rate declared in the engine XML, which the generated HTML must match. */
    const val FRAME_RATE = 30

    private const val APP_NAME = "{{APP_NAME}}"
    private const val BASE = "{{BASE_W}}/{{BASE_H}}"
    private const val FRAME_RATE_PH = "{{FRAME_RATE}}"
    private const val SAFE_ZONE = "{{SAFE_ZONE}}"
    private const val CONTENT = "{{CONTENT}}"
    private const val LANGS = "{{LANGS}}"

    fun buildPrompt(
        appName: String,
        baseWidth: Int,
        baseHeight: Int,
        content: String,
        safeZone: String = "Enabled, BASE_CAM = floor(BASE_W/3), all content x >= BASE_CAM+20",
        langs: String = "Follow the language in which CONTENT is written",
    ): String = GUIDELINE
        .replace(APP_NAME, appName)
        .replace(BASE, "$baseWidth/$baseHeight")
        .replace(FRAME_RATE_PH, FRAME_RATE.toString())
        .replace(SAFE_ZONE, safeZone)
        .replace(CONTENT, content)
        .replace(LANGS, langs)

    val GUIDELINE: String = """
        # Generate a single-file HTML application for Xiaomi Widget v2 WebView

        ## 0. Parameters (fill these in for each use; preserve the remaining specification)
        - APP_NAME: $APP_NAME
        - BASE_W × BASE_H: design resolution (**the rear screen is landscape, BASE_W > BASE_H; copy the actual device resolution without swapping dimensions**). Express all drawing coordinates in this space.
        - FRAME_RATE: $FRAME_RATE_PH
        - SAFE_ZONE: reserve the left third, **enabled unconditionally unless CONTENT explicitly states that this application has no left exclusion zone**:
          BASE_CAM = floor(BASE_W/3), all content x ≥ BASE_CAM+20 (this request: $SAFE_ZONE)
        - CONTENT: $CONTENT
        - LANGS: **default to the language of CONTENT (see section 7); create language mappings in the constants section only when multilingual support is explicitly requested, with zh-CN as the fallback** (this request: $LANGS)

        ## Mandatory safe zone (takes precedence over layout descriptions in CONTENT; violations invalidate the output)
        1. Exclusion zone = x∈[0, BASE_CAM], BASE_CAM=floor(BASE_W/3). All content, including cards, text, buttons,
           instruments, and decorations, must satisfy `x ≥ BASE_CAM+20` and `x+w ≤ BASE_W-20`.
           Backgrounds and full-screen overlays may cross this boundary: the rule constrains content, not background colors.
        2. Declare all rectangles in one unique `LAYOUT` constants section at the top. Drawing functions
           **must not contain coordinate literals**; use only `LAYOUT.xxx` references so every safe-zone bound can be audited.
        3. The HTML must begin with the following comments; **missing comments are defects**. Compute every number from LAYOUT; never copy the sample numbers:
           ```html
           <!-- SAFE_ZONE: BASE_CAM=325 | CARD.x=345 OK | min_x=345 >= 345 OK -->
           <!-- AVAIL: 611x556 ratio=1.10 | OUTER 611x556 fill=100% | CHILD fillW=96% fillH=92% OK -->
           <!-- TOUCH: START 88x60 OK | RESTART 80x56 OK | gap>=16 OK -->
           ```
           (The example uses BASE=976×596. Recompute using your actual BASE.)
        4. In `__DIAL_NO_ANIM` mode, draw a red dashed exclusion-zone outline with `strokeRect(0,0,BASE_CAM,BASE_H)` for visual inspection.

        ## Mandatory usable-area proportions and touch targets (violations invalidate the output)
        1. **Compute the usable area**: `AVAIL = (x=BASE_CAM+20, y=20, w=BASE_W-BASE_CAM-40, h=BASE_H-40)`.
           After excluding the left third, the usable area is **nearly square** (976×596→611×556≈1.10; 904×572→563×532≈1.06),
           not a narrow portrait phone strip or a wide banner. **Design all layouts for this nearly square area**,
           and record the computed AVAIL values in the header comments.
        2. **Mandatory outer container**: the first rectangle in LAYOUT must be an `OUTER` backing plate covering the usable area
           (x/y/w/h ≈ AVAIL, with approximately 10–20px margin permitted on each edge). Draw all children inside it.
           OUTER must fill the usable right-hand display: **do not leave large empty regions or collapse it into a strip**.
        3. **Fill both dimensions**: CHILD is the bounding box of all rectangles except OUTER. Require:
           - `CHILD.w ≥ OUTER.w × 0.80` **and** `CHILD.h ≥ OUTER.h × 0.80`;
           - CHILD's aspect-ratio deviation from AVAIL must be ≤25%; **no tall narrow strips or thin horizontal strips**;
           - record computed fill percentages and ratios on the AVAIL header line, not merely "checked".
        4. **Organize the rear screen into horizontal zones** (percentages of AVAIL height, not fixed coordinates):
           title/status around 0–14%, main display around 16–74%, actions around 76–100%,
           each approximately 100% of the usable width; alternatively use left/center/right columns.
           **Do not copy a portrait phone's single narrow column of cards.**
        5. **Minimum touch size and readability**:
           - every tappable rectangle **≥60×60** BASE pixels (roughly 5mm on screen); primary buttons are recommended to be ≥80×56;
           - adjacent tappable rectangles must be separated by ≥16px, without overlap or direct contact; expand hit bounds by 10–15px;
           - body text ≥14px, key button text ≥16px, and `fitFont` minimum 12px;
           - enumerate each tappable rectangle's dimensions on the TOUCH header line.

        ## 1. Runtime environment (hard requirements)
        1. Deliver **one complete HTML file** with `<!doctype html>`, the correct `lang`, and all CSS/JS inline.
        2. The page loads as a local resource (`assets/web/index.html`, `local="true"`):
           **no network requests, CDNs, external fonts, or external images**. It must work entirely offline.
        3. The engine executes page code directly through `WebViewCommand runjs`. Expose these functions on window and make them
           **idempotent, repeatable, reversible, and exception-free**:
           - `window.__fit()` — called during engine init with `params="'__fit()'" delay="2000"`
           - `window.__setAod(1/0)` — enterAod/pause passes 1; exitAod/resume/init passes 0
        4. **Target screens vary and may be high-density**. One implementation must support multiple resolutions with unknown dpr ∈ {1,2,3}.
           Never hardcode a specific screen size.
        5. Include this deployment reminder in a header comment: `The engine uses cachePage="true"; clear the cache or reinstall the widget after editing the file.`

        ## 2. Resolution adaptation (every rule is mandatory)
        1. Include this meta tag in `<head>`; otherwise WebView uses its default 980-wide layout viewport, causing clipping or blurry scaling:
           ```html
           <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
           ```
        2. CSS **must not contain concrete pixel dimensions** such as `width:976px`:
           ```css
           html,body{margin:0;background:#000;width:100%;height:100%;overflow:hidden}
           canvas{display:block;width:100%;height:100%;touch-action:none}
           ```
           (If the engine sets `transparentSurface="true"` and transparency is required, use `background:transparent` on body
           and do not paint an opaque full-screen background.)
        3. Express all drawing in BASE coordinates. Obtain the actual viewport only through `__fit()`, using this template:
           ```js
           var BASE_CAM=Math.floor(BASE_W/3);              // Set to 0 only when CONTENT explicitly disables the left exclusion zone
           function __fit(){
             var dpr=window.devicePixelRatio||1;
             W=window.CANVAS_W||window.innerWidth||c.width;   // Prefer host injection; innerWidth is the main viewport source
             H=window.CANVAS_H||window.innerHeight||c.height;
             var bw=Math.round(W*dpr),bh=Math.round(H*dpr);
             if(c.width!==bw)c.width=bw;                      // Physical-pixel backing prevents blur
             if(c.height!==bh)c.height=bh;
             ctx.setTransform(dpr,0,0,dpr,0,0);               // Must follow c.width assignment: changing dimensions resets transforms
             CAM=Math.floor(W/3);
             FIT=Math.min((W-CAM)/(BASE_W-BASE_CAM),H/BASE_H);
             DX=Math.round((CAM+(W-CAM)/2)-((BASE_CAM+BASE_W)/2)*FIT);
             DY=Math.round((H-BASE_H*FIT)/2);
             window.__DIAL_CANVAS_SIZE={width:W,height:H};
           }
           __fit();
           window.__fit=__fit;
           window.addEventListener('resize',__fit);
           ```
           Without a left exclusion zone, use `FIT=Math.min(W/BASE_W,H/BASE_H);`
           and `DX=Math.round((W-BASE_W*FIT)/2); DY=Math.round((H-BASE_H*FIT)/2);`.
           Never use `c.width` as the primary viewport source, assume dpr==1, omit resetting transforms after resizing the canvas,
           or substitute `outerWidth/screen.width` for `innerWidth`.
        4. Every frame paints the background across the entire `W×H` viewport under the dpr transform.
           Wrap content in `save(); translate(DX,DY); scale(FIT,FIT); …; restore();`.
           Full-screen overlays (dimming/flashes) must inverse-transform to viewport coordinates:
           `fillRect(-DX/FIT,-DY/FIT,W/FIT,H/FIT)`.
        5. Touch mapping: `getBoundingClientRect()` (CSS pixels) → normalize by `×W/rect.width` → subtract DX/DY → divide by FIT
           → test rectangles in BASE coordinates. **W/H are CSS pixels; canvas attributes are multiplied by dpr. Never mix them.**
        6. After transformation, the content's left edge must not enter viewport `(0..CAM)` (the transformed safe-zone check).

        ## 3. Lifecycle contract (runjs targets must always remain callable)
        1. Manage rAF with a pending sentinel to prevent duplicate chains or accelerated playback:
           ```js
           var rafPend=false,paused=false;
           function kick(){if(rafPend)return;rafPend=true;requestAnimationFrame(frame);}
           ```
        2. `__setAod(v)`: `v=1` sets paused; the last queued frame returns immediately at entry, ending the chain
           without updating, drawing, or audio. `v=0` clears paused, sets `lastDraw=-1e9`, and calls kick.
           Repeated calls with the same value return immediately; init's `__setAod(0)` must never create a second chain.
        3. Use elapsed differences only, never catch-up: dt is a `Date.now()` difference **clamped to ≤200ms**, avoiding jumps after waking.
        4. `window.__DIAL_NO_ANIM=true` (engine freeze-frame/preview, one-way switch) executes only
           `setupDemo();draw(DEMO_T);` once, without starting a loop. Stay silent in this mode and while AOD-paused.
        5. Provide `window.__debugSize=function(){return [W,H,devicePixelRatio,c.width,c.height,FIT].join(' ')};`
           so runjs can verify adaptation.

        ## 4. Rendering and performance
        1. Throttle the rAF main loop with `FRAME_MS=1000/FRAME_RATE`; skip work when `document.hidden` while preserving the chain.
        2. For continuous physics, use 120Hz fixed substeps (`PHYS_DT=1000/120`, `MAX_SUB=8` prevents spiraling; discard excess time).
        3. Cache gradients by parameter key; draw glows in local unit space (translate+scale, coordinates always 0..1).
        4. Use integer font sizes and cache font strings; adaptive font sizing has a 12px floor. Guard corner radii with `Number.isFinite`.
        5. Derive all animation/particle positions from frame time vt for deterministic freeze-frames; remove alert/console.log debug output.

        ## 5. Input
        Use Pointer Events only; single-pointer `dragId` semantics plus `setPointerCapture`; call preventDefault on pointermove/contextmenu.
        Expand tappable hit bounds by 10–15px. The first pointerdown is the only entry point for creating AudioContext.

        ## 6. Persistence
        Wrap all localStorage access in try/catch. After reading, validate each field with `Number.isFinite`, clamping, and type checks.
        Ignore write failures silently; storage errors must never affect the main loop.

        ## 7. Text and internationalization
        - **Highest-priority language rule: all generated HTML UI text must use the language in which CONTENT is written**:
          titles, buttons, hints, units, result text, and constants must all follow it. Do not mix languages
          or switch languages without permission. English CONTENT means English UI; Chinese CONTENT means Chinese UI.
        - Put all UI strings in the top `T_*` constants section with a comment stating "For four-language support, replace only this section".
          Do not scatter string literals in any language throughout the implementation. Include units such as km/h, N, and m in these constants.

        ## 8. Audio
        Create audio lazily on the first gesture; throttle the same key to 55ms; wrap audio operations in try/catch with silent fallback.
        Explicitly release nodes through start/stop rather than caching them. Remain silent in `__DIAL_NO_ANIM` and while AOD-paused.

        ## 9. Freeze-frame
        Provide `setupDemo()` to construct a deterministic, representative in-progress scene with all key values populated.
        In `__DIAL_NO_ANIM`, draw only one frame; its result must not depend on randomness, real time, or sound.

        ## 10. Output requirements
        Output only the complete HTML file, without explanatory prefixes or suffixes. Implement every section in actual code.
        No placeholders such as `...` or `// same as above`.

        ## 11. Delivery checklist (verify every item before delivery)
        - [ ] **All three HTML header comments are present with computed values: SAFE_ZONE enumerates every rectangle x ≥BASE_CAM+20;
              AVAIL records OUTER≈usable area, CHILD fill ≥80% in both axes, aspect-ratio deviation ≤25%;
              TOUCH lists every tappable rectangle ≥60×60 with spacing ≥16**
        - [ ] **Horizontal zones (top/main/bottom or three columns), not a portrait phone's narrow single column;
              no large empty region on the usable right-hand display and no thin strip of content**
        - [ ] No concrete `number px` dimensions in CSS; viewport meta exists
        - [ ] `__fit`/`__setAod` exposed on window; `setTransform` follows canvas dimension assignments; resize listener installed
        - [ ] Target screen A: `__debugSize()` matches the actual display, backing=CSS×dpr; no clipped or inflated corners
        - [ ] Target screen B (or BASE itself): also passes with FIT=1.0
        - [ ] Sharp edges at dpr=2/3, without apparent overall enlargement
        - [ ] runjs `__fit()`, `__setAod(1)`, `__setAod(0)` each called twice consecutively: no exceptions, acceleration, or duplicate chains
        - [ ] Wake after ≥5 seconds with the display off: no position or physics jump
        - [ ] Complete the main flow: no console errors or network requests
        - [ ] **All UI text uses exactly the language of CONTENT, without mixing languages**
        - [ ] `__DIAL_NO_ANIM`: deterministic single frame, silent, with exclusion-zone outline visible
        - [ ] Header includes the cachePage cache-clearing reminder
        - [ ] Output contains HTML only

        ## 12. Prohibited
        Hardcoded resolution/viewport; assuming dpr==1; relying on the default 980-wide viewport; external resources or third-party libraries;
        setTimeout-driven main loop; catch-up frames without clamping dt; alert/console.log left in the release;
        missing runjs target functions for any Trigger (calling a nonexistent function is a defect);
        UI text whose language differs from CONTENT;
        content rectangles with x < BASE_CAM+20; missing SAFE_ZONE rectangle enumeration in header comments; coordinate literals in drawing functions;
        **tall narrow or thin horizontal content (CHILD fill <80% in either axis or aspect-ratio deviation >25%); missing OUTER or failure to fill the usable area;
        tappable rectangles <60×60; touch spacing <16; swapped BASE dimensions (rear screen requires width>height); copying a portrait phone's single-column layout.**
    """.trimIndent()
}
