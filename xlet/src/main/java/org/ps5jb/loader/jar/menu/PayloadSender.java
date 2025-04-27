package org.ps5jb.loader.jar.menu;

import org.ps5jb.loader.Status;

import java.io.*;
import java.net.Socket;

import java.net.HttpURLConnection;
import java.net.URL;

public class PayloadSender {

    public static void sendPayloadFromUrl(String url) {
        Status.println("Trying to send " + url + " to elfldr on port 9021...");

        HttpURLConnection connection = null;
        Socket elfldrSocket = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {

                InputStream inputStream = connection.getInputStream();
                elfldrSocket = new Socket("127.0.0.1", 9021);
                OutputStream outputStream = elfldrSocket.getOutputStream();

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                Status.println(url + " was sent to elfldr on port 9021.");
            } else {
                Status.println("Failed to read payload url " + connection.getResponseMessage());
            }
        } catch (Exception e) {
            Status.printStackTrace("Failed to open elfldr socket", e);
        } finally {
            if (elfldrSocket != null) {
                try {
                    elfldrSocket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (connection != null)
            {
                try {
                    if (connection.getInputStream() != null)
                        connection.getInputStream().close();
                    if (connection.getOutputStream() != null)
                        connection.getOutputStream().close();
                    if (connection.getErrorStream() != null)
                        connection.getErrorStream().close();
                    connection.disconnect();
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    }

    public static void sendPayloadFromFile(File elfFile) {
        Status.println("Trying to send " + elfFile.getAbsolutePath() + " to elfldr on port 9021...");

        FileInputStream fileInputStream = null;
        Socket elfldrSocket = null;
        try {
            fileInputStream = new FileInputStream(elfFile);
            elfldrSocket = new Socket("127.0.0.1", 9021);
            OutputStream outputStream = elfldrSocket.getOutputStream();

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();

            Status.println(elfFile.getAbsolutePath() + " was sent to elfldr on port 9021.");
        } catch (FileNotFoundException e) {
            Status.printStackTrace("Detected invalid file to send", e);
        } catch (IOException e) {
            Status.printStackTrace("Failed to open elfldr socket", e);
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (elfldrSocket != null) {
                try {
                    elfldrSocket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
