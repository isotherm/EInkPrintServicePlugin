package com.alpaxo.einkprintserviceplugin;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import com.android.volley.NetworkResponse;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EInkPrintService extends PrintService {
    private static final String LOG_TAG = "EInkPrintService";

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        Log.d(LOG_TAG, "MyPrintService#onCreatePrinterDiscoverySession() called");

        return new PrinterDiscoverySession() {
            @Override
            public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
                Log.d(LOG_TAG, "PrinterDiscoverySession#onStartPrinterDiscovery(priorityList: " + priorityList + ") called");
                VssApi api = new VssApi(EInkPrintService.this);
                api.getDevicesCollection(new Response.Listener<JSONArray>() {
                    @Override
                    public void onResponse(JSONArray response) {
                        for (int i = 0; i < response.length(); i++) {
                            int width, height;
                            String uuid, name, id;
                            int status;
                            try {
                                JSONObject device = response.getJSONObject(i);
                                Log.v(LOG_TAG, device.toString());
                                uuid = device.getString("Uuid");
                                status = PrinterInfo.STATUS_UNAVAILABLE;
                                if (device.getString("State").equals("online")) {
                                    status = PrinterInfo.STATUS_IDLE;
                                }

                                JSONObject options = device.getJSONObject("Options");
                                if (options.has("Name")) {
                                    name = options.getString("Name");
                                } else {
                                    name = uuid;
                                }

                                JSONArray displays = device.getJSONArray("Displays");
                                // TODO: Handle more than one display?
                                int j = 0;
                                JSONObject display = displays.getJSONObject(j);
                                id = "eink" + Integer.toString(j);
                                if ((display.getInt("Rotation") & 1) == 1) {
                                    width = display.getInt("Height");
                                    height = display.getInt("Width");
                                    id += "p";
                                } else {
                                    width = display.getInt("Width");
                                    height = display.getInt("Height");
                                    id += "l";
                                }
                            } catch(JSONException e) {
                                Log.e(LOG_TAG, e.toString());
                                continue;
                            }

                            final List<PrinterInfo> printers = new ArrayList<>();
                            final PrinterId printerId = generatePrinterId(uuid);
                            final PrinterInfo.Builder builder = new PrinterInfo.Builder(printerId, name, status);
                            final PrinterCapabilitiesInfo.Builder capBuilder = new PrinterCapabilitiesInfo.Builder(printerId);
                            final String label = Integer.toString(width) + "x" + Integer.toString(height);
                            PrintAttributes.MediaSize mediaSize = new PrintAttributes.MediaSize(
                                    id, label, width * 10, height * 10);
                            capBuilder.addMediaSize(mediaSize, true);
                            capBuilder.addResolution(new PrintAttributes.Resolution("default",
                                            "Native DPI", 300, 300),
                                    true);
                            capBuilder.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME,
                                    PrintAttributes.COLOR_MODE_MONOCHROME);
                            builder.setCapabilities(capBuilder.build());
                            printers.add(builder.build());
                            addPrinters(printers);
                        }
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(LOG_TAG, error.toString());
                    }
                });
            }

            @Override
            public void onStopPrinterDiscovery() {
                Log.d(LOG_TAG, "MyPrintService#onStopPrinterDiscovery() called");
            }

            @Override
            public void onValidatePrinters(List<PrinterId> printerIds) {
                Log.d(LOG_TAG, "MyPrintService#onValidatePrinters(printerIds: " + printerIds + ") called");
            }

            @Override
            public void onStartPrinterStateTracking(PrinterId printerId) {
                Log.d(LOG_TAG, "MyPrintService#onStartPrinterStateTracking(printerId: " + printerId + ") called");
            }

            @Override
            public void onStopPrinterStateTracking(PrinterId printerId) {
                Log.d(LOG_TAG, "MyPrintService#onStopPrinterStateTracking(printerId: " + printerId + ") called");
            }

            @Override
            public void onDestroy() {
                Log.d(LOG_TAG, "MyPrintService#onDestroy() called");
            }
     };
    }

    @Override
    protected void onPrintJobQueued(PrintJob printJob) {
        Log.d(LOG_TAG, "queued: " + printJob.getId().toString());
        final PrintDocument document = printJob.getDocument();
        final FileDescriptor documentFd = document.getData().getFileDescriptor();
        final PrintJobInfo info = printJob.getInfo();
        final String uuid = info.getPrinterId().getLocalId();
        final PrintAttributes.MediaSize mediaSize = info.getAttributes().getMediaSize();

        AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
            File tempDir;
            File tempFile = null;

            @Override
            protected void onPreExecute() {
                printJob.start();

                Log.d(LOG_TAG, "Creating temporary file");
                this.tempDir = EInkPrintService.this.getCacheDir();
                try {
                    this.tempFile = File.createTempFile("page", ".pdf", tempDir);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "", e);
                }

                Log.d(LOG_TAG, "Writing document to temporary file");
                try (FileInputStream fis = new FileInputStream(documentFd);
                     FileOutputStream fos = new FileOutputStream(this.tempFile)) {
                    int c;
                    byte[] bytes = new byte[4096];
                    while((c = fis.read(bytes)) != -1){
                        fos.write(bytes, 0, c);
                    }
                } catch(Exception e) {
                    Log.e(LOG_TAG, "", e);
                }
            }

            @Override
            protected String doInBackground(Void... params) {
                Log.d(LOG_TAG, "Rendering document to image");
                int width = mediaSize.getWidthMils() / 10;
                int height = mediaSize.getHeightMils() / 10;
                Log.d(LOG_TAG, "Media size: " + Integer.toString(width)
                    + "x" + Integer.toString(height));

                final Bitmap bitmap;
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tempFile,
                        ParcelFileDescriptor.MODE_READ_WRITE);
                     PdfRenderer renderer = new PdfRenderer(pfd);
                     PdfRenderer.Page page = renderer.openPage(0)) {
                    Log.d(LOG_TAG, "Page size: " + Integer.toString(page.getWidth())
                            + "x" + Integer.toString(page.getHeight()));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xffffffff);
                    Matrix transform = new Matrix();
                    float sx = (float)width / page.getWidth();
                    float sy = (float)height / page.getHeight();
                    float scale = Math.min(sx, sy);
                    transform.setScale(scale, scale);
                    //float scale = Math.max(sx, sy);
                    //transform.setTranslate(-page.getWidth() / 2, -page.getHeight() / 2);
                    //transform.postScale(scale, scale);
                    //transform.postTranslate(width / 2, height / 2);
                    if (mediaSize.getId().endsWith("p") != (height > width)) {
                        transform.postRotate(90);
                        transform.postTranslate(height, 0);
                    }
                    page.render(bitmap, null, transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "", e);
                    return e.getMessage();
                }

                Log.d(LOG_TAG, "Preparing to push bitmap to device");
                final VssApi api = new VssApi(EInkPrintService.this);
                api.putImage(uuid, bitmap, new Response.Listener<NetworkResponse>() {
                    @Override
                    public void onResponse(NetworkResponse response) {
                        // success
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(LOG_TAG, error.networkResponse.allHeaders.toString());
                        Log.e(LOG_TAG, error.networkResponse.data.toString());
                    }
                });
                return null;
            }

            @Override
            protected void onPostExecute(String message) {
                if (message != null) {
                    printJob.fail(message);
                } else {
                    printJob.complete();
                }
                if (this.tempFile != null) {
                    this.tempFile.delete();
                }
            }
        };
        task.execute();
    }

    private static String toString(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Byte.toString(b)).append(',');
        }
        if (sb.length() != 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        Log.d(LOG_TAG, "canceled: " + printJob.getId().toString());

        printJob.cancel();
    }
}
