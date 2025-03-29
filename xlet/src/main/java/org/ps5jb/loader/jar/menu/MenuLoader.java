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

import java.net.HttpURLConnection;
import java.net.URL;

public class MenuLoader extends HContainer implements Runnable, UserEventListener, JarLoader {
    private static String[] discPayloadList;
    private static String[] remotePayloadList = null;
    private static String remotePayloadBaseUrl = "http://172.245.146.114:8000";

    private boolean active = true;
    private boolean terminated = false;
    private boolean waiting = false;
    private int terminateRemoteJarLoaderPressCount;

    private Ps5MenuLoader ps5MenuLoader;

    private File discPayloadPath = null;
    private JarLoader remoteJarLoaderJob = null;
    private Thread remoteJarLoaderThread = null;

    public MenuLoader() throws IOException {
        ps5MenuLoader = initMenuLoader();
    }

    @Override
    public void run() {
        EventManager em = EventManager.getInstance();
        UserEventRepository evRep = new OverallRepository();

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
                        } else if (remoteJarLoaderThread != null) {
                            try {
                                // Wait on remote JAR loader to finish
                                remoteJarLoaderThread.join();
                            } catch (InterruptedException e) {
                                // Ignore
                            }
                            remoteJarLoaderThread = null;
                            remoteJarLoaderJob = null;
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
        } catch (RuntimeException | Error | IOException ex) {
            Status.printStackTrace("Unhandled exception", ex);
            terminated = true;
        } finally {
            em.removeUserEventListener(this);
        }
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
                discPayloadList = dir.list();
            }

            if (discPayloadList == null) {
                discPayloadList = new String[0];
            }
        }
        return discPayloadList;
    }


    /**
     * Returns a list of ELF files that are present on remote http server.
     *
     * @return Array of sendable ELF files or an empty list if there are none.
     */
    public static String[] listRemoteElfPayloads() {
        HttpURLConnection connection = null;
        String[] tempPayloadList = new String[0];

        try {
            connection = (HttpURLConnection) new URL(remotePayloadBaseUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String body = new String();
                byte[] buffer = new byte[4096];
                int bytesRead = -1;

                InputStream input = connection.getInputStream();
                while ((bytesRead = input.read(buffer)) != -1) {
                    body = body + new String(buffer, 0, bytesRead);
                }
                input.close();

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

                    remotePayloadList = new String[count];
                    System.arraycopy(tempPayloadList, 0, remotePayloadList, 0, tempPayloadList.length);
                    remotePayloadList[count-1] = elf_file;
                    tempPayloadList = remotePayloadList;
                }
            } else {
                Status.println("Failed to obtain list of elfs from remote");
            }
        } catch (Exception e) {
            Status.println(e.getMessage());
        }

        return remotePayloadList;
    }

    private Ps5MenuLoader initMenuLoader() throws IOException {
        Ps5MenuLoader ps5MenuLoader = new Ps5MenuLoader(new Ps5MenuItem[]{
                new Ps5MenuItem("Remote JAR loader", "wifi_icon.png"),
                new Ps5MenuItem("Disk JAR loader", "disk_icon.png"),
                new Ps5MenuItem("Remote ELF sender", "internet_icon.png")
        });

        // init disk jar loader sub items
        final String[] jarPayloads = listJarPayloads();
        final Ps5MenuItem[] diskSubItems = new Ps5MenuItem[jarPayloads.length];
        for (int i = 0; i < jarPayloads.length; i++) {
            final String payload = jarPayloads[i];
            diskSubItems[i] = new Ps5MenuItem(payload, null);
        }
        ps5MenuLoader.setSubmenuItems(2, diskSubItems);

        initRemoteElfSender(ps5MenuLoader);

        return ps5MenuLoader;
    }


    private void reloadMenuLoader() throws IOException {
        Ps5MenuLoader oldMenuLoader = ps5MenuLoader;

        discPayloadList = null;
        ps5MenuLoader = initMenuLoader();
        ps5MenuLoader.setSelected(oldMenuLoader.getSelected());
        ps5MenuLoader.setSelectedSub(oldMenuLoader.getSelectedSub());
        ps5MenuLoader.setSubMenuActive(oldMenuLoader.isSubMenuActive());
    }

    private void initRemoteElfSender(Ps5MenuLoader ps5MenuLoader) throws IOException {
        // init remote elf sender sub items
        final String[] elfPayloads = listRemoteElfPayloads();
        final Ps5MenuItem[] remoteElfSubItems = new Ps5MenuItem[elfPayloads.length];
        for (int i = 0; i < elfPayloads.length; i++) {
            final String payload = elfPayloads[i];
            remoteElfSubItems[i] = new Ps5MenuItem(payload, null);
        }
        ps5MenuLoader.setSubmenuItems(3, remoteElfSubItems);
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
                case HRcEvent.VK_RIGHT:
                    if (ps5MenuLoader.getSelected() < ps5MenuLoader.getMenuItems().length) {
                        ps5MenuLoader.setSelected(ps5MenuLoader.getSelected() + 1);
                    }
                    switch(ps5MenuLoader.getSelected()) {
                        case 1:
                            ps5MenuLoader.setSubMenuActive(false);
                            break;
                        case 2:
                            ps5MenuLoader.setSubMenuActive(true);
                            break;
                        case 3:
                            ps5MenuLoader.setSubMenuActive(true);
                            try {
                                initRemoteElfSender(ps5MenuLoader);
                            } catch (IOException e) {
                                Status.printStackTrace("Error initRemoteElfSender()", e);
                            }
                            break;
                    }
                    break;

                case HRcEvent.VK_LEFT:
                    if (ps5MenuLoader.getSelected() > 1) {
                        ps5MenuLoader.setSelected(ps5MenuLoader.getSelected() - 1);
                    }
                    switch(ps5MenuLoader.getSelected()) {
                        case 1:
                            ps5MenuLoader.setSubMenuActive(false);
                            break;
                        case 2:
                            ps5MenuLoader.setSubMenuActive(true);
                            break;
                        case 3:
                            ps5MenuLoader.setSubMenuActive(true);
                            try {
                                initRemoteElfSender(ps5MenuLoader);
                            } catch (IOException e) {
                                Status.printStackTrace("Error initRemoteElfSender()", e);
                            }
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
                    } else if (ps5MenuLoader.getSelected() == 1 && remoteJarLoaderThread == null) {
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
                    } else if (ps5MenuLoader.getSelected() == 2) {
                        Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub() - 1];
                        discPayloadPath = new File(Config.getLoaderPayloadPath(), selectedItem.getLabel());
                        active = false;
                    }  else if (ps5MenuLoader.getSelected() == 3) {
                        if (remotePayloadList.length > 0) {
                            Ps5MenuItem selectedItem = ps5MenuLoader.getSubmenuItems(ps5MenuLoader.getSelected())[ps5MenuLoader.getSelectedSub() - 1];
                            String elfToSend = remotePayloadBaseUrl + "/" + selectedItem.getLabel();
                            PayloadSender.sendPayloadFromUrl(elfToSend);
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
