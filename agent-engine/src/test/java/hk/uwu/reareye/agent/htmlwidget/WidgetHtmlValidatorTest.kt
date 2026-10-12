package hk.uwu.reareye.agent.htmlwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target device is a 904×572 landscape rear screen.
 *
 * Derived values, recomputed by hand so these tests check the validator rather than restate it:
 *
 *   BASE_CAM  = floor(904/3) = 301
 *   content x must be >= 301+20 = 321,  and x+w <= 904-20 = 884
 *   AVAIL     = (x=321, y=20, w=904-301-40=563, h=572-40=532)
 *
 * The control fixture uses OUTER w=553 at x=341, so its right edge is 894 — deliberately past 884,
 * because the spec allows the backing plate a margin while forbidding content from crossing.
 */
class WidgetHtmlValidatorTest {

    private val baseW = 904
    private val baseH = 572

    private fun goodHtml(
        safeZoneHeader: String = "<!-- SAFE_ZONE: BASE_CAM=301 | CARD.x=361 OK | min_x=361 >= 321 OK -->",
        availHeader: String = "<!-- AVAIL: 563x532 ratio=1.06 | OUTER 553x552 fill=100% | CHILD fillW=93% fillH=91% OK -->",
        touchHeader: String = "<!-- TOUCH: START 160x88 OK | RESTART 140x80 OK | gap=24 >= 16 OK -->",
        cardX: Int = 361,
    ): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
        $safeZoneHeader
        $availHeader
        $touchHeader
        <!-- 引擎 cachePage="true"，改文件后需清缓存或重装小部件才生效 -->
        <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
        <style>
        html,body{margin:0;background:#000;width:100%;height:100%;overflow:hidden}
        canvas{display:block;width:100%;height:100%;touch-action:none}
        </style>
        </head>
        <body>
        <canvas id="c"></canvas>
        <script>
        var BASE_W=904, BASE_H=572;
        var BASE_CAM=Math.floor(BASE_W/3);
        var FRAME_MS=1000/30;
        var LAYOUT = {
          OUTER:   { x:341, y:10,  w:553, h:552, touch:false },
          HEADER:  { x:361, y:32,  w:513, h:70,  touch:false },
          LABEL:   { x:361, y:210, w:513, h:90,  touch:false },
          START:   { x:${cardX + 16}, y:430, w:160, h:88, touch:true },
          RESTART: { x:${cardX + 200}, y:438, w:140, h:80, touch:true }
        };
        var c=document.getElementById('c'), ctx=c.getContext('2d');
        var W=0,H=0,CAM=0,FIT=1,DX=0,DY=0;
        var rafPend=false,paused=false,lastDraw=-1e9,vt=0;
        function __fit(){
          var dpr=window.devicePixelRatio||1;
          W=window.CANVAS_W||window.innerWidth||c.width;
          H=window.CANVAS_H||window.innerHeight||c.height;
          var bw=Math.round(W*dpr), bh=Math.round(H*dpr);
          if(c.width!==bw)c.width=bw;
          if(c.height!==bh)c.height=bh;
          ctx.setTransform(dpr,0,0,dpr,0,0);
          CAM=Math.floor(W/3);
          FIT=Math.min((W-CAM)/(BASE_W-BASE_CAM),H/BASE_H);
          DX=Math.round((CAM+(W-CAM)/2)-((BASE_CAM+BASE_W)/2)*FIT);
          DY=Math.round((H-BASE_H*FIT)/2);
          window.__DIAL_CANVAS_SIZE={width:W,height:H};
        }
        function draw(t){
          ctx.fillRect(0,0,W,H);
          ctx.save(); ctx.translate(DX,DY); ctx.scale(FIT,FIT);
          ctx.fillRect(LAYOUT.OUTER.x,LAYOUT.OUTER.y,LAYOUT.OUTER.w,LAYOUT.OUTER.h);
          ctx.fillRect(LAYOUT.HEADER.x,LAYOUT.HEADER.y,LAYOUT.HEADER.w,LAYOUT.HEADER.h);
          ctx.fillRect(LAYOUT.LABEL.x,LAYOUT.LABEL.y,LAYOUT.LABEL.w,LAYOUT.LABEL.h);
          ctx.fillRect(LAYOUT.START.x,LAYOUT.START.y,LAYOUT.START.w,LAYOUT.START.h);
          ctx.fillRect(LAYOUT.RESTART.x,LAYOUT.RESTART.y,LAYOUT.RESTART.w,LAYOUT.RESTART.h);
          if(window.__DIAL_NO_ANIM){ ctx.strokeRect(0,0,BASE_CAM,BASE_H); }
          ctx.restore();
        }
        function setupDemo(){ vt=1500; }
        function frame(ts){
          rafPend=false;
          if(paused) return;
          if(ts-lastDraw<FRAME_MS){ kick(); return; }
          lastDraw=ts;
          draw(vt);
        }
        function kick(){ if(rafPend) return; rafPend=true; requestAnimationFrame(frame); }
        window.__fit=__fit;
        window.__setAod=function(v){
          if(v===1){ if(paused) return; paused=true; return; }
          if(!paused) return;
          paused=false; lastDraw=-1e9; kick();
        };
        window.__debugSize=function(){ return [W,H,devicePixelRatio,c.width,c.height,FIT].join(' '); };
        __fit();
        window.addEventListener('resize',__fit);
        if(window.__DIAL_NO_ANIM){ setupDemo(); draw(1500); } else { kick(); }
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun validate(html: String) = WidgetHtmlValidator.validate(html, baseW, baseH)

    private fun codes(html: String) = validate(html).errors.map { it.code }

    @Test
    fun `a well-formed widget passes`() {
        val result = validate(goodHtml())
        assertTrue("expected no errors, got ${result.report()}", result.passed)
    }

    /**
     * OUTER is a backing plate. The spec permits the background to extend past the content boundary,
     * and asks OUTER to leave margins, so it must not be held to the content rule.
     */
    @Test
    fun `OUTER may extend past the content boundary`() {
        val result = validate(goodHtml())
        val outer = result.layout.first { it.name == "OUTER" }
        assertTrue("fixture should exercise the overhang", outer.rect.right > baseW - 20)
        assertFalse("safe_zone_violation" in result.errors.map { it.code })
    }

    @Test
    fun `content in the dead zone is rejected`() {
        assertTrue("safe_zone_violation" in codes(goodHtml(cardX = 100)))
    }

    /** `setTransform` placed before the canvas resize is the classic blurry-scaling bug. */
    @Test
    fun `transform before canvas resize is rejected`() {
        val html = goodHtml().replace(
            "if(c.width!==bw)c.width=bw;",
            "ctx.setTransform(1,0,0,1,0,0); if(c.width!==bw)c.width=bw;",
        )
        assertTrue("transform_order" in codes(html))
    }

    @Test
    fun `a header that lies about BASE_CAM is rejected`() {
        val html = goodHtml(
            safeZoneHeader = "<!-- SAFE_ZONE: BASE_CAM=999 | CARD.x=361 OK | min_x=361 >= 321 OK -->",
        )
        assertTrue("header_mismatch" in codes(html))
    }

    @Test
    fun `a header that lies about min_x is rejected`() {
        val html = goodHtml(
            safeZoneHeader = "<!-- SAFE_ZONE: BASE_CAM=301 | CARD.x=361 OK | min_x=1 >= 321 OK -->",
        )
        assertTrue("header_mismatch" in codes(html))
    }

    @Test
    fun `a header that lies about AVAIL is rejected`() {
        val html = goodHtml(availHeader = "<!-- AVAIL: 100x100 ratio=1.0 | OUTER 553x552 fill=100% -->")
        assertTrue("header_mismatch" in codes(html))
    }

    @Test
    fun `missing header comments are rejected`() {
        val found = codes(goodHtml(safeZoneHeader = "", availHeader = "", touchHeader = ""))
        assertTrue("expected missing_header, got $found", "missing_header" in found)
    }

    /**
     * A phone-shaped narrow column: fills height but not width.
     *
     * Every content rectangle has to be narrowed, because CHILD is the *union* of all of them — a
     * single wide rectangle would keep the bounding box wide and hide the problem.
     */
    @Test
    fun `a tall narrow column layout is rejected`() {
        val html = goodHtml()
            .replace(
                "HEADER:  { x:361, y:32,  w:513, h:70,  touch:false },",
                "HEADER:  { x:361, y:32,  w:120, h:70,  touch:false },",
            )
            .replace(
                "LABEL:   { x:361, y:210, w:513, h:90,  touch:false },",
                "LABEL:   { x:361, y:210, w:120, h:90,  touch:false },",
            )
            .replace(
                "START:   { x:377, y:430, w:160, h:88, touch:true },",
                "START:   { x:361, y:430, w:60, h:88, touch:true },",
            )
            .replace(
                "RESTART: { x:561, y:438, w:140, h:80, touch:true }",
                "RESTART: { x:361, y:438, w:60, h:80, touch:true }",
            )
        val found = codes(html)
        assertTrue("expected a fill or ratio error, got $found", "child_fill" in found || "child_ratio" in found)
    }

    @Test
    fun `a touch target under the minimum size is rejected`() {
        val html = goodHtml().replace(
            "START:   { x:377, y:430, w:160, h:88, touch:true },",
            "START:   { x:377, y:430, w:40, h:30, touch:true },",
        )
        assertTrue("touch_too_small" in codes(html))
    }

    @Test
    fun `touch targets packed closer than the gap minimum are rejected`() {
        // START spans 377..537; moving RESTART to 541 leaves only a 4px gap.
        val html = goodHtml().replace(
            "RESTART: { x:561, y:438, w:140, h:80, touch:true }",
            "RESTART: { x:541, y:438, w:140, h:80, touch:true }",
        )
        assertTrue("touch_gap" in codes(html))
    }

    @Test
    fun `coordinate literals in drawing code are rejected`() {
        val html = goodHtml().replace(
            "ctx.fillRect(LAYOUT.START.x,LAYOUT.START.y,LAYOUT.START.w,LAYOUT.START.h);",
            "ctx.fillRect(400, 430, 160, 88);",
        )
        assertTrue("coordinate_literal" in codes(html))
    }

    /** The background is painted across the whole viewport and the spec allows that. */
    @Test
    fun `a full-viewport background fill is not a coordinate literal`() {
        assertFalse("coordinate_literal" in codes(goodHtml()))
    }

    @Test
    fun `hardcoded css pixel sizes are rejected`() {
        val html = goodHtml().replace("width:100%;height:100%", "width:976px;height:596px")
        assertTrue("hardcoded_css_px" in codes(html))
    }

    @Test
    fun `an external resource is rejected`() {
        val html = goodHtml().replace(
            "<canvas id=\"c\"></canvas>",
            "<canvas id=\"c\"></canvas><img src=\"https://example.com/a.png\">",
        )
        assertTrue("external_resource" in codes(html))
    }

    @Test
    fun `leftover debug output is rejected`() {
        assertTrue("debug_output" in codes(goodHtml().replace("function draw(t){", "function draw(t){ console.log('x');")))
        assertTrue("debug_output" in codes(goodHtml().replace("function draw(t){", "function draw(t){ alert('x');")))
    }

    @Test
    fun `a missing viewport meta is rejected`() {
        val html = goodHtml().replace(
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">",
            "",
        )
        assertTrue("missing_viewport" in codes(html))
    }

    @Test
    fun `a missing lifecycle hook is rejected`() {
        assertTrue("missing_hook" in codes(goodHtml().replace("window.__setAod=function(v)", "function notExposed(v)")))
    }

    @Test
    fun `a missing still-frame branch is rejected`() {
        assertTrue("missing_dial_no_anim" in codes(goodHtml().replace("__DIAL_NO_ANIM", "__SOMETHING_ELSE")))
    }

    /**
     * The report is fed back to the model when asking for a corrected revision, so it must name the
     * problems rather than just say "invalid".
     */
    @Test
    fun `the report lists every error with its code`() {
        val report = validate(goodHtml(cardX = 100)).report()
        assertTrue(report.contains("safe_zone_violation"))
        assertTrue(report.contains("321"))
    }
}
