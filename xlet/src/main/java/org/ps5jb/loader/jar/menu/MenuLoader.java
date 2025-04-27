package org.ps5jb.loader.jar.menu;

import org.dvb.event.EventManager;
import org.dvb.event.OverallRepository;
import org.dvb.event.UserEvent;
import org.dvb.event.UserEventListener;
import org.dvb.event.UserEventRepository;
import org.havi.ui.HContainer;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;
import org.havi.ui.event.HRcEvent;
import org.ps5jb.loader.Config;
import org.ps5jb.loader.Status;
import org.ps5jb.loader.jar.JarLoader;
import org.ps5jb.loader.jar.RemoteJarLoader;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class MenuLoader extends HContainer implements Runnable, UserEventListener, JarLoader {
    private static String[] discPayloadList;
    private static String[] remoteElfList = null;
    private static String[] remoteJarList = null;
    private static String[] pipelineList;
    private static UserEventRepository evRep = new OverallRepository();

    private boolean active = true;
    private boolean terminated = false;
    private boolean waiting = false;
    private int terminateRemoteJarLoaderPressCount;

    private Ps5MenuLoader ps5MenuLoader;

    private File discPayloadPath = null;
    private File remotePayloadPath = null;
    private File pipelinePath = null;

    private JarLoader remoteJarLoaderJob = null;
    private Thread remoteJarLoaderThread = null;

    public MenuLoader() throws IOException {
        ps5MenuLoader = initMenuLoader();
    }

    @Override
    public void run() {
        EventManager em = EventManager.getInstance();

        Status.println("MenuLoader starting...");
        for (String payload : listJarPayloads()) {
            Status.println("[Payload] " + payload);
        }

        em.addUserEventListener(this, evRep);
        try {
            while (!terminated) {
                if (!active) {
                    if (!waiting) {
                        if (discPayloadPath != null) {
                            em.removeUserEventListener(this);
                            try {
                                loadJar(discPayloadPath, false);
                            } catch (InterruptedException e) {
                                // Ignore
                            } catch (Throwable ex) {
                                // JAR execution didn't work, notify and wait to return to the menu
                                Status.printStackTrace("Could not load JAR from disc", ex);
                            } finally {
                                em.addUserEventListener(this, evRep);
                            }
                            discPayloadPath = null;
                        } else if (remotePayloadPath != null) {
                            em.removeUserEventListener(this);
                            try {
                                loadJar(remotePayloadPath, true);
                            } catch (InterruptedException e) {
                                // Ignore
                            } catch (Throwable ex) {
                                // JAR execution didn't work, notify and wait to return to the menu
                                Status.printStackTrace("Could not load JAR from disc", ex);
                            } finally {
                                em.addUserEventListener(this, evRep);
                            }
                            remotePayloadPath = null;
                        } else if (remoteJarLoaderThread != null) {
                            try {
                                // Wait on remote JAR loader to finish
                                remoteJarLoaderThread.join();
                            } catch (InterruptedException e) {
                                // Ignore
                            }
                            remoteJarLoaderThread = null;
                            remoteJarLoaderJob = null;
                        } else if (pipelinePath != null) {
                            PipelineRunner.runPipeline(pipelinePath, this);
                            pipelinePath = null;
                        }

                        // Reload the menu in case paths to payloads changed after JAR execution
                        reloadMenuLoader();

                        // Wait for user input before returning
                        Status.println("Press X to return to the menu");
                        waiting = true;
                    } else {
                        Thread.yield();
                    }
                } else {
                    initRenderLoop();
                }
            }
        } catch (RuntimeException | Error  ex) {
            Status.printStackTrace("Unhandled exception", ex);
            terminated = true;
        } finally {
            em.removeUserEventListener(this);
        }
    }

    public static UserEventRepository getUserEventRepository() {
        return evRep;
    }

    /**
     * Returns a list of JAR files that are present on disc.
     *
     * @return Array of loadable JAR files or an empty list if there are none.
     */
    public static String[] listJarPayloads() {
        if (discPayloadList == null) {
            final File dir = Config.getLoaderPayloadPath();
            if (dir.isDirectory() && dir.canRead()) {
                discPayloadList = dir.list((dir1, name) -> name.toLowerCase().endsWith(".jar"));
            }

            if (discPayloadList == null) {
                discPayloadList = new String[0];
            }
        }
        return discPayloadList;
    }

    /**
     * Returns a list of Pipeline files that are present on /.
     *
     * @return Array of loadable JAR files or an empty list if there are none.
     */
    public static String[] listPipelines() {
        if (pipelineList == null) {
            final File dir = Config.getLoaderPayloadPath();
            if (dir.isDirectory() && dir.canRead()) {
                pipelineList = dir.list((dir1, name) -> name.toLowerCase().endsWith(".pipe"));
            }

            if (pipelineList == null) {
                pipelineList = new String[0];
            }
        }
        return pipelineList;
    }

    /**
     * Returns a list of ELF files that are present on remote http server.
     *
     * @return Array of sendable ELF files or an empty list if there are none.
     */
    public static String[] listRemoteElfPayloads() {
        HttpURLConnection connection = null;
        String[] tempPayloadList = new String[0];

        if (!Config.isRemoteUp())
        {
            remoteElfList = new String[0];
            return remoteElfList;
        }

        try {
            connection = (HttpURLConnection) new URL(Config.getRemotePayloadBaseUrl()).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String body = new String();
                byte[] buffer = new byte[8192];
                int bytesRead = -1;

                InputStream input = connection.getInputStream();
                while ((bytesRead = input.read(buffer)) != -1) {
                    body = body + new String(buffer, 0, bytesRead);
                }

                int href_idx = body.indexOf("href=\"");
                int href_end_idx = -1;
                int count = 0;

                while (href_idx >= 0) {
                    count++;
                    body = body.substring(href_idx + 6);
                    href_end_idx = body.indexOf("\"");
                    String elf_file = body.substring(0, href_end_idx);
                    body = body.substring(href_end_idx+1);
                    href_idx = body.indexOf("href=\"");

                    remoteElfList = new String[count];
                    System.arraycopy(tempPayloadList, 0, remoteElfList, 0, tempPayloadList.length);
                    remoteElfList[count-1] = elf_file;
                    tempPayloadList = remoteElfList;
                }
            } else {
                Status.println("Failed to obtain list of elfs from remote");
            }
        } catch (Exception e) {
            Status.printStackTrace("Failed to obtain list of ELFs", e);
            remoteElfList = new String[0];
        } finally {
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

        return remoteElfList;
    }

    /**
     * Returns a list of JAR files that are present on remote http server.
     *
     * @return Array of sendable ELF files or an empty list if there are none.
     */
    public static String[] listRemoteJarPayloads() {
        HttpURLConnection connection = null;
        String[] tempPayloadList = new String[0];

        if (!Config.isRemoteUp())
        {
            remoteJarList = new String[0];
            return remoteJarList;
        }

        try {
            connection = (HttpURLConnection) new URL(Config.getRemoteJarBaseUrl()).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String body = new String();
                byte[] buffer = new byte[8192];
                int bytesRead = -1;

                InputStream input = connection.getInputStream();
                while ((bytesRead = input.read(buffer)) != -1) {
                    body = body + new String(buffer, 0, bytesRead);
                }

                int href_idx = body.indexOf("href=\"");
                int href_end_idx = -1;
                int count = 0;

                while (href_idx >= 0) {
                    count++;
                    body = body.substring(href_idx + 6);
                    href_end_idx = body.indexOf("\"");
                    String elf_file = body.substring(0, href_end_idx);
                    body = body.substring(href_end_idx+1);
                    href_idx = body.indexOf("href=\"");

                    remoteJarList = new String[count];
                    System.arraycopy(tempPayloadList, 0, remoteJarList, 0, tempPayloadList.length);
                    remoteJarList[count-1] = elf_file;
                    tempPayloadList = remoteJarList;
                }
            } else {
                Status.println("Failed to obtain list of JARs from remote");
            }
        } catch (Exception e) {
            Status.printStackTrace("Failed to obtain list of JARs", e);
            remoteJarList = new String[0];
        } finally {
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

        return remoteJarList;
    }

    private Ps5MenuLoader initMenuLoader() {
        Ps5MenuLoader ps5MenuLoader = new Ps5MenuLoader(new Ps5MenuItem[] {
            new Ps5MenuItem(Method.REMOTE_LOADER, "Remote JAR loader", "wifi_icon.png"),
            new Ps5MenuItem(Method.PIPELINE_LOADER, "Pipeline runner", "pipeline_icon.png"),
            new Ps5MenuItem(Method.DISC_LOADER, "Disk JAR loader", "disk_icon.png"),
            new Ps5MenuItem(Method.REMOTE_ELF_SENDER, "Remote ELF sender", "internet_icon.png"),
            new Ps5MenuItem(Method.REMOTE_JAR_SENDER, "Remote JAR sender", "internet_icon.png"),
        });

        initPipelinesloader(ps5MenuLoader);
        initDiscLoader(ps5MenuLoader);
        initRemoteElfSender(ps5MenuLoader);
        initRemoteJarSender(ps5MenuLoader);

        return ps5MenuLoader;
    }

    private void initPipelinesloader(Ps5MenuLoader ps5MenuLoader) {
        final String[] pipelines = listPipelines();

        final Ps5MenuItem[] pipelinesSubItems = new Ps5MenuItem[pipelines.length];
        for (int i = 0; i < pipelines.length; i++) {
            final String payload = pipelines[i];
            pipelinesSubItems[i] = new Ps5MenuItem(Method.PIPELINE_LOADER, payload, null);
        }
        ps5MenuLoader.setSubmenuItems(Method.PIPELINE_LOADER, pipelinesSubItems);
    }

    private void initDiscLoader(Ps5MenuLoader ps5MenuLoader) {
        // init disk jar loader sub items
        final String[] jarPayloads = listJarPayloads();
        final Ps5MenuItem[] diskSubItems = new Ps5MenuItem[jarPayloads.length];
        for (int i = 0; i < jarPayloads.length; i++) {
            final String payload = jarPayloads[i];
            diskSubItems[i] = new Ps5MenuItem(Method.DISC_LOADER, payload, null);
        }
        ps5MenuLoader.setSubmenuItems(Method.DISC_LOADER, diskSubItems);
    }

    private File downloadRemoteJarPayload(String url)
    {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
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
                    }
                    Status.println("Received " + totalSize + " bytes...Done", true); 
                    
                    return jarFile; 
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

        return null;
    }

    private void reloadMenuLoader() {
        Ps5MenuLoader oldMenuLoader = ps5MenuLoader;

        discPayloadList = null;
        ps5MenuLoader = initMenuLoader();
        ps5MenuLoader.setSelected(oldMenuLoader.getSelected());
        ps5MenuLoader.setSelectedSub(oldMenuLoader.getSelectedSub());
        ps5MenuLoader.setSubMenuActive(oldMenuLoader.isSubMenuActive());
    }

    private void initRemoteJarSender(Ps5MenuLoader ps5MenuLoader) {
        // init remote jar sender sub items
        final String[] jarPayloads = listRemoteJarPayloads();
      
        final Ps5MenuItem[] remoteJarSubItems = new Ps5MenuItem[jarPayloads.length];
        for (int i = 0; i < jarPayloads.length; i++) {
            final String payload = jarPayloads[i];
            remoteJarSubItems[i] = new Ps5MenuItem(Method.REMOTE_JAR_SENDER, payload, null);
        }
        ps5MenuLoader.setSubmenuItems(Method.REMOTE_JAR_SENDER, remoteJarSubItems);
    }

    private void initRemoteElfSender(Ps5MenuLoader ps5MenuLoader) {
        // init remote elf sender sub items
        final String[] elfPayloads = listRemoteElfPayloads();

        final Ps5MenuItem[] remoteElfSubItems = new Ps5MenuItem[elfPayloads.length];
        for (int i = 0; i < elfPayloads.length; i++) {
            final String payload = elfPayloads[i];
            remoteElfSubItems[i] = new Ps5MenuItem(Method.REMOTE_ELF_SENDER, payload, null);
        }
        ps5MenuLoader.setSubmenuItems(Method.REMOTE_ELF_SENDER, remoteElfSubItems);
    }

    private void initRenderLoop() {
        setSize(Config.getLoaderResolutionWidth(), Config.getLoaderResolutionHeight());
        setBackground(Color.darkGray);
        setForeground(Color.lightGray);
        setVisible(true);

        HScene scene = HSceneFactory.getInstance().getDefaultHScene();
        scene.add(this, BorderLayout.CENTER, 0);

        try {
            scene.validate();
            while (active) {
                scene.repaint();
                Thread.yield();
            }
        } finally {
            this.setVisible(false);
            scene.remove(this);
        }
    }

    @Override
    public void userEventReceived(UserEvent userEvent) {
        if (userEvent.getType() == HRcEvent.KEY_RELEASED) {
            // Exit early if running a payload and not waiting for specific user input
            boolean isTerminateRemoteJarLoaderSeq = false;
            if (!active) {
                isTerminateRemoteJarLoaderSeq =
                        ((userEvent.getCode() == HRcEvent.VK_3) && (terminateRemoteJarLoaderPressCount == 0)) ||
                                ((userEvent.getCode() == HRcEvent.VK_2) && (terminateRemoteJarLoaderPressCount == 1)) ||
                                ((userEvent.getCode() == HRcEvent.VK_1) && (terminateRemoteJarLoaderPressCount == 2));
                if (!isTerminateRemoteJarLoaderSeq) {
                    if (!waiting || (userEvent.getCode() != HRcEvent.VK_ENTER)) {
                        return;
                    }
                }
            }

            // Reset sequence to exit Remote JAR loader
            if (!isTerminateRemoteJarLoaderSeq && (terminateRemoteJarLoaderPressCount > 0) && remoteJarLoaderThread != null) {
                terminateRemoteJarLoaderPressCount = 0;
            }
            
            switch (userEvent.getCode()) {
                case HRcEvent.VK_3:
                case HRcEvent.VK_2:
                case HRcEvent.VK_1:
                    if (isTerminateRemoteJarLoaderSeq) {
                        if (terminateRemoteJarLoaderPressCount == 2) {
                            if (remoteJarLoaderJob != null) {
                                try {
                                    remoteJarLoaderJob.terminate();
                                    terminateRemoteJarLoaderPressCount = 0;
                                } catch (Throwable ex) {
                                    Status.printStackTrace("Remote JAR loader could not be terminated.", ex);
                                }
                            }
                        } else {
                            ++terminateRemoteJarLoaderPressCount;
                        }
                    }
                    break;
                case HRcEvent.VK_4:
                    active = !active;
                    break;
                case HRcEvent.VK_RIGHT:
                    if (ps5MenuLoader.getSelected() < ps5MenuLoader.getMenuItems().length) {
                        ps5MenuLoader.setSelected(ps5MenuLoader.getSelected() + 1);
                    }
                    switch(ps5MenuLoader.getSelected()) {
                        case Method.REMOTE_LOADER:
                            ps5MenuLoader.setSubMenuActive(false);
                            break;
                        case Method.DISC_LOADER:
                            ps5MenuLoader.setSubMenuActive(true);
                            break;
                        case Method.REMOTE_JAR_SENDER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initRemoteJarSender(ps5MenuLoader);
                            break;
                        case Method.REMOTE_ELF_SENDER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initRemoteElfSender(ps5MenuLoader);
                            break;
                        case Method.PIPELINE_LOADER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initPipelinesloader(ps5MenuLoader);
                            break;
                    }
                    break;

                case HRcEvent.VK_LEFT:
                    if (ps5MenuLoader.getSelected() > 1) {
                        ps5MenuLoader.setSelected(ps5MenuLoader.getSelected() - 1);
                    }
                    switch(ps5MenuLoader.getSelected()) {
                        case Method.REMOTE_LOADER:
                            ps5MenuLoader.setSubMenuActive(false);
                            break;
                        case Method.DISC_LOADER:
                            ps5MenuLoader.setSubMenuActive(true);
                            break;
                        case Method.REMOTE_JAR_SENDER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initRemoteJarSender(ps5MenuLoader);
                            break;
                        case Method.REMOTE_ELF_SENDER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initRemoteElfSender(ps5MenuLoader);
                            break;
                        case Method.PIPELINE_LOADER:
                            ps5MenuLoader.setSubMenuActive(true);
                            initPipelinesloader(ps5MenuLoader);
                            break;
                    }
                    break;

                case HRcEvent.VK_DOWN:
                    if (ps5MenuLoader.isSubMenuActive() && ps5MenuLoader.getSelectedSub() < ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected()).length) {
                        ps5MenuLoader.setSelectedSub(ps5MenuLoader.getSelectedSub() + 1);
                    }
                    break;

                case HRcEvent.VK_UP:
                    if (ps5MenuLoader.isSubMenuActive() && ps5MenuLoader.getSelectedSub() > 1) {
                        ps5MenuLoader.setSelectedSub(ps5MenuLoader.getSelectedSub() - 1);
                    }
                    break;

                case HRcEvent.VK_ENTER: // X button
                    if (waiting) {
                        active = true;
                        waiting = false;
                    } else if (ps5MenuLoader.getSelected() == Method.REMOTE_LOADER && remoteJarLoaderThread == null) {
                        try {
                            remoteJarLoaderJob = new RemoteJarLoader(Config.getLoaderPort());
                            remoteJarLoaderThread = new Thread(remoteJarLoaderJob, "RemoteJarLoader");

                            // Notify the user that this is a one time switch and that BD-J restart is required to return to the menu
                            Status.println("Starting remote JAR loader. To return to the loader menu, press 3-2-1");
                            remoteJarLoaderThread.start();
                        } catch (Throwable ex) {
                            Status.printStackTrace("Remote JAR loader could not be initialized. Press X to continue", ex);
                            waiting = true;
                        }
                        active = false;
                    } else if (ps5MenuLoader.getSelected() == Method.DISC_LOADER) {
                        Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub()-1];
                        File payloadFile = new File(Config.getLoaderPayloadPath(), selectedItem.getLabel());
                        if (selectedItem.getLabel().toLowerCase().endsWith(".jar")) {
                            discPayloadPath = payloadFile;
                        } else {
                            PayloadSender.sendPayloadFromFile(payloadFile);
                        }
                        active = false;
                    }  else if (ps5MenuLoader.getSelected() == Method.REMOTE_JAR_SENDER) {
                        if (remoteJarList.length > 0) {
                            Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub()-1];
                            String jarToSend = Config.getRemoteJarBaseUrl() + "/" + selectedItem.getLabel();
                            remotePayloadPath = downloadRemoteJarPayload(jarToSend);
                            active = false;
                        }
                    } else if (ps5MenuLoader.getSelected() == Method.REMOTE_ELF_SENDER) {
                        if (remoteElfList.length > 0) {
                            Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub()-1];
                            String elfToSend = Config.getRemotePayloadBaseUrl() + "/" + selectedItem.getLabel();
                            PayloadSender.sendPayloadFromUrl(elfToSend);
                            active = false;
                        }
                    }  else if (ps5MenuLoader.getSelected() == Method.PIPELINE_LOADER) {
                        if (pipelineList.length > 0) {
                            Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub()-1];
                            pipelinePath = new File(Config.getLoaderPayloadPath(), selectedItem.getLabel());
                            active = false;
                        }
                    }
                    break;
            }
        }
    }

    @Override
    public void paint(Graphics graphics) {
        if (active) {
            ps5MenuLoader.renderMenu(graphics);
        }

        super.paint(graphics);
    }

    @Override
    public void terminate() throws IOException {
        this.active = false;
        this.terminated = true;
    }
}
