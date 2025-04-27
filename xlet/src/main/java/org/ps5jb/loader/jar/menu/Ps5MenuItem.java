package org.ps5jb.loader.jar.menu;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.InputStream;

public class Ps5MenuItem {

    private int type;
    private final String label;
    private Image icon;

    public Ps5MenuItem(int type, String label, String imagePath) {
        this.type = type;
        this.label = label;

        if (imagePath != null) {
            InputStream iconStream = this.getClass().getResourceAsStream(imagePath);
            if (iconStream == null) {
                return;
            }

            byte[] iconBytes;
            try {
                int iconSize = iconStream.available();
                int iconWriteStart = 0;
                int iconRead = 0;
                iconBytes = new byte[iconSize];
                while ((iconWriteStart < iconSize) && (iconRead != -1)) {
                    iconRead = iconStream.read(iconBytes, iconWriteStart, iconSize);
                    if (iconRead > 0) {
                        iconWriteStart += iconRead;
                    }
                }
                this.icon = Toolkit.getDefaultToolkit().createImage(iconBytes);
            } catch (Exception e) {
                // do nothing
            } finally {
                try {
                    iconStream.close();
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    }

    public int getType() {
        return type;
    }
    
    public String getLabel() {
        return label;
    }

    public Image getIcon() {
        return icon;
    }
}
