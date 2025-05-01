package org.ps5jb.loader.jar.menu;

import org.dvb.event.EventManager;
import org.dvb.event.OverallRepository;
import org.dvb.event.UserEvent;
import org.dvb.event.UserEventRepository;
import org.havi.ui.event.HRcEvent;
import org.ps5jb.loader.Config;
import org.ps5jb.loader.Status;
import org.ps5jb.loader.jar.JarLoader;

import java.awt.Menu;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class PipelineRunner {

    public static void runPipeline(File pipelinePath, JarLoader jarLoader) {
        try {
            parsePipeline(pipelinePath, jarLoader);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void parsePipeline(File pipelinePath, JarLoader jarLoader) throws Exception {
        final BufferedReader br = new BufferedReader(new FileReader(pipelinePath));
        String line;
        int queue_idx = 1;

        while ((line = br.readLine()) != null) {
            if (line != null && line.length() > 0) {
                if (line.toLowerCase().startsWith("jar disc ")) {
                    final String jarFileName = line.substring("jar disc ".length()).trim();
                    final File discFile = new File(Config.getLoaderPayloadPath(), jarFileName);
                    if (discFile.exists()) {
                        jarLoader.loadJar(discFile, false);
                    } else {
                        Status.println("Pipeline error: Could not find disc file " + jarFileName);
                        break;
                    }
                } else if (line.toLowerCase().startsWith("jar remote ")) {
                    final String jarFileName = line.substring("jar remote ".length()).trim();
                    String url = Config.getRemotePayloadBaseUrl() + "/" + jarFileName;
                    final File jarFile =  MenuLoader.downloadPayload(url);
                    if (jarFile.exists()) {
                        jarLoader.loadJar(jarFile, true);
                    } else {
                        Status.println("Pipeline error: Could not download file " + jarFileName);
                        break;
                    }
                } else if (line.toLowerCase().startsWith("elf remote ")) {
                    final String elfFileName  = line.substring("elf remote ".length()).trim();
                    String url = Config.getRemotePayloadBaseUrl() + "/" + elfFileName;
                    PayloadSender.sendPayloadFromUrl(url);
                } else if (line.toLowerCase().startsWith("sleep ")) {
                    final String sleepSeconds = line.substring("sleep ".length());
                    Status.println("Pipeline: Sleeping for " + sleepSeconds + " seconds...");
                    Thread.sleep(new Integer(sleepSeconds) * 1000);
                } else if (line.toLowerCase().startsWith("queue elf remote ")) {
                    final String elfFileName  = line.substring("queue elf remote ".length()).trim();
                    String url = Config.getRemotePayloadBaseUrl() + "/" + elfFileName;
                    File queueFile = new File(Config.getPayloadQueuePath(), String.valueOf(queue_idx) + "." + elfFileName);
                    if (MenuLoader.downloadPayload(url, queueFile) == null)
                    {
                        Status.println("Pipeline error: Failed to download elf");
                        break;
                    }
                    queue_idx = queue_idx + 1;
                } else if (line.toLowerCase().startsWith("start-payload-loader")) {
                    File payloadLoader = new File(Config.getLoaderPayloadPath(), "payload-loader-for-ps5-jar-loader.elf");
                    PayloadSender.sendPayloadFromFile(payloadLoader);
                }
            }
        }
    }

    private static File findUsbPayloads() {
        File usbPayloadRoot = null;

        // search for usb0 - usb7
        for (int i = 0; i < 8; i++) {
            try {
                File f = new File("/mnt/usb" + i);
                if (f.exists() && f.list((dir, name) -> name.toLowerCase().endsWith(".jar")
                        || name.toLowerCase().endsWith(".elf")
                        || name.toLowerCase().endsWith(".bin")).length > 0) {
                    Status.println("Found usb with payloads on " + f.getAbsolutePath());
                    usbPayloadRoot = f;
                    break;
                }
            } catch (Exception ex) {
                Status.println("Error searching for usb" + i);
            }
        }

        if (usbPayloadRoot != null && usbPayloadRoot.isDirectory() && usbPayloadRoot.canRead()) {
            return usbPayloadRoot;
        }

        Status.println("Pipeline error: could not find any usb payloads");
        return null;
    }
}
