package org.ps5jb.loader.jar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.ps5jb.loader.Status;

public class RemoteJarSender implements JarLoader {
    private String url;

    /**
     * JarLoader constructor.
     *
     * @param url full URL for JAR
     */
    public RemoteJarSender(String url) {
        this.url = url;
    }

    @Override
    public void run() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(this.url).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();

                Path jarPath = Files.createTempFile("jarLoader", ".jar");
                File jarFile = jarPath.toFile();

                try {
                    jarFile.deleteOnExit();

                    Status.println("Receiving JAR data to: " + jarFile);
                    byte[] buf = new byte[8192];
                    int readCount;
                    int totalSize = 0;
                    OutputStream jarOut = Files.newOutputStream(jarPath);
                    try {
                        while ((readCount = inputStream.read(buf)) != -1) {
                            jarOut.write(buf, 0, readCount);

                            Status.println("Received " + totalSize + " bytes...", totalSize != 0);
                            totalSize += readCount;
                        }
                    } finally {
                        jarOut.close();
                        inputStream.close();
                    }
                    Status.println("Received " + totalSize + " bytes...Done", true);                

                    this.loadJar(jarFile, true);
                } catch (IOException | RuntimeException | Error e) {
                    deleteTempJar(jarFile);
                    throw e;
                }    
            } else {
                Status.println("Failed to read JAR " + connection.getResponseMessage());
            }
        } catch (Exception e) {
            Status.printStackTrace("Failed to get JAR", e);
        } finally {
            if (connection != null)
            {
                try {
                    connection.getInputStream().close();
                    connection.getOutputStream().close();;
                    connection.disconnect();
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    }

    @Override
    public void terminate() throws IOException {
    }
}
