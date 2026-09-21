package com.alcove.sop;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean offlineFallbackLoaded = false;

    // PDF page size in points (A4 at 72pt/inch).
    private static final int PAGE_WIDTH_PT = 595;
    private static final int PAGE_HEIGHT_PT = 842;

    // The SOP now lives on GitHub Pages so content edits (including tables,
    // colours, whole new sections — everything, not just the six prose
    // sections the Google Sheet patches) go live instantly with no APK
    // rebuild. The bundled assets/sop.html copy is kept only as an offline
    // fallback for when this can't be reached.
    private static final String REMOTE_SOP_URL = "https://alcove-realty.github.io/alcove-sop/";
    private static final String REMOTE_SOP_HOST = "alcove-realty.github.io";
    private static final String LOCAL_SOP_URL = "file:///android_asset/sop.html";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The whole point of loading the SOP live from GitHub Pages is that
        // content edits show up without a rebuild — a cached copy defeats
        // that, so always fetch fresh instead of the WebView's default
        // HTTP-cache behaviour.
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new PrintBridge(), "AndroidPrint");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Let our own page (remote or the local fallback) load in the
                // WebView as normal; hand anything else (e.g. the register
                // links in the Related Documents section) to the system
                // browser, same as before this WebViewClient was added.
                if (REMOTE_SOP_HOST.equals(uri.getHost()) || "file".equals(uri.getScheme())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    toast("Could not open link: " + e.getMessage());
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !offlineFallbackLoaded) {
                    offlineFallbackLoaded = true;
                    view.loadUrl(LOCAL_SOP_URL);
                }
            }
        });
        webView.loadUrl(REMOTE_SOP_URL);
        setContentView(webView);
    }

    private class PrintBridge {
        @JavascriptInterface
        public void print(final String lang) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    saveAsPdf(lang);
                }
            });
        }

        /**
         * Opens Android's own print dialog (real printers included, not just
         * "save as PDF") via the standard, documented WebView print API —
         * unlike saveAsPdf() above, this does NOT try to drive the dialog
         * headlessly; it just hands the WebView to the system print spooler
         * the normal way, so there is no PrintDocumentAdapter-construction
         * problem here at all.
         */
        @JavascriptInterface
        public void printDialog(final String lang) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    android.print.PrintManager printManager =
                            (android.print.PrintManager) getSystemService(PRINT_SERVICE);
                    String jobName = "Alcove-Document-Control-SOP-" + lang;
                    printManager.print(jobName, webView.createPrintDocumentAdapter(jobName), null);
                }
            });
        }
    }

    /**
     * Renders the WebView's full content straight into a PDF file in
     * Downloads — no printer picker, no system print dialog. Android's
     * PrintDocumentAdapter can't be driven headlessly (its callback classes
     * are only constructible by the system print spooler), so instead this
     * temporarily grows the WebView to its full content height, draws it
     * into one tall bitmap, slices that into A4-sized pages, and writes a
     * PdfDocument built from those slices.
     */
    private void saveAsPdf(final String lang) {
        Toast.makeText(this, "Preparing PDF…", Toast.LENGTH_SHORT).show();
        webView.evaluateJavascript("document.body.scrollHeight", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String heightStr) {
                int contentHeight;
                try {
                    contentHeight = (int) Double.parseDouble(heightStr);
                } catch (Exception e) {
                    contentHeight = webView.getHeight();
                }
                renderToBitmapThenPdf(lang, Math.max(contentHeight, webView.getHeight()));
            }
        });
    }

    private void renderToBitmapThenPdf(final String lang, final int contentHeight) {
        final int width = webView.getWidth();
        final ViewGroup.LayoutParams originalParams = webView.getLayoutParams();
        final int originalHeight = originalParams.height;

        ViewGroup.LayoutParams grown = webView.getLayoutParams();
        grown.height = contentHeight;
        webView.setLayoutParams(grown);
        webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.EXACTLY));
        webView.layout(0, 0, width, contentHeight);

        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(0xFFFBFAF6); // paper-raised fallback behind any transparent bits
                webView.draw(canvas);

                // restore the on-screen WebView to its normal size immediately.
                ViewGroup.LayoutParams restored = webView.getLayoutParams();
                restored.height = originalHeight;
                webView.setLayoutParams(restored);
                webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(webView.getMeasuredHeight(), View.MeasureSpec.UNSPECIFIED));

                writeBitmapAsPdf(bitmap, lang);
            }
        }, 350);
    }

    private void writeBitmapAsPdf(Bitmap bitmap, String lang) {
        float scale = (float) PAGE_WIDTH_PT / bitmap.getWidth();
        int sliceHeightPx = Math.max(1, (int) (PAGE_HEIGHT_PT / scale));

        PdfDocument pdfDocument = new PdfDocument();
        int y = 0;
        int pageNum = 1;
        while (y < bitmap.getHeight()) {
            PdfDocument.PageInfo pageInfo =
                    new PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pageNum).create();
            PdfDocument.Page page = pdfDocument.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.save();
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, -y, null);
            canvas.restore();
            pdfDocument.finishPage(page);
            y += sliceHeightPx;
            pageNum++;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String filename = "Alcove-Document-Control-SOP-" + lang + "-" + stamp + ".pdf";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            pdfDocument.close();
            bitmap.recycle();
            toast("Could not create the PDF file.");
            return;
        }

        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            pdfDocument.writeTo(os);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            toast("Saved to Downloads: " + filename);
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            toast("PDF save failed: " + e.getMessage());
        } finally {
            pdfDocument.close();
            bitmap.recycle();
        }
    }

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
