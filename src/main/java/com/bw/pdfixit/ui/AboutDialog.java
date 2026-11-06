package com.bw.pdfixit.ui;

import com.bw.pdfixit.AboutInfo;
import com.bw.pdfixit.BuildVersion;
import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * The About dialog: version, build date, license, the system, and the libraries and fonts with their licenses.
 */
public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(Component parent) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), I18n.t("about.title", AboutInfo.NAME),
                JDialog.ModalityType.APPLICATION_MODAL);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;

        // Name, build and description.
        JPanel texts = new JPanel(new GridBagLayout());
        GridBagConstraints tc = new GridBagConstraints();
        tc.gridx = 0;
        tc.gridy = 0;
        tc.fill = GridBagConstraints.HORIZONTAL;
        tc.weightx = 1;
        tc.anchor = GridBagConstraints.WEST;
        HtmlLabel title = new HtmlLabel(AboutInfo.NAME + " " + BuildVersion.VERSION);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.8f));
        texts.add(title, tc);

        tc.gridy++;
        HtmlLabel subtitle = new HtmlLabel(I18n.t("app.subtitle"));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, subtitle.getFont().getSize2D() * 1.2f));
        texts.add(subtitle, tc);

        tc.gridy++;
        tc.insets = new Insets(6, 0, 0, 0);
        texts.add(new HtmlLabel(I18n.t("about.buildDate", BuildVersion.BUILD_DATE)), tc);

        tc.gridy++;
        tc.insets = new Insets(8, 0, 0, 0);
        texts.add(new HtmlLabel("<html><body style=\"width:525px\">" + I18n.t("about.description") + "</body></html>"), tc);

        JPanel head = new JPanel(new BorderLayout(24, 0));
        head.add(texts, BorderLayout.CENTER);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        content.add(head, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        gc.gridy++;
        gc.insets = new Insets(14, 0, 0, 0);
        content.add(link(AboutInfo.HOME_PAGE), gc);

        // The license and the button to show its text.
        gc.gridy++;
        gc.insets = new Insets(10, 0, 0, 0);
        content.add(licenseRow(dialog), gc);

        gc.gridy++;
        gc.insets = new Insets(10, 0, 0, 0);
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.weighty = 1;
        JEditorPane details = new JEditorPane("text/html", details());
        details.setEditable(false);
        details.setCaretPosition(0);
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null)
                browse(e.getURL().toString());
        });
        JScrollPane scroll = new JScrollPane(details);
        scroll.setPreferredSize(new Dimension(910, 240));
        content.add(scroll, gc);

        JButton copy = new JButton(I18n.t("about.copy"));
        copy.setToolTipText(I18n.t("about.copyTip"));
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(AboutInfo.asText()), null));
        JButton close = new JButton(I18n.t("about.close"));
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(copy);
        buttons.add(close);

        dialog.add(content, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 20, 14, 20));
        south.add(buttons, BorderLayout.EAST);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Java, system, libraries and fonts with their licenses, as shown in the details area.
     */
    private static String details() {
        StringBuilder sb = new StringBuilder("<html><body style=\"font-family:SansSerif\">");
        sb.append("<b>Java</b> ").append(escape(AboutInfo.java())).append("<br>");
        sb.append("<b>" + I18n.t("about.system") + "</b> ").append(escape(AboutInfo.os())).append("<br><br>");
        sb.append("<b>" + I18n.t("about.libraries") + "</b><table cellspacing=\"0\" cellpadding=\"2\">");
        for (AboutInfo.Component c : AboutInfo.components()) {
            sb.append("<tr><td>").append(escape(c.name()));
            if (!c.version().isEmpty())
                sb.append(' ').append(escape(c.version()));
            sb.append("</td><td>&nbsp;&nbsp;");
            if (c.licenseUrl() == null)
                sb.append(escape(c.license()));
            else
                sb.append("<a href=\"").append(c.licenseUrl()).append("\">").append(escape(c.license())).append("</a>");
            sb.append("</td></tr>");
        }
        sb.append("</table><br><b>" + I18n.t("about.data") + "</b><table cellspacing=\"0\" cellpadding=\"2\">");
        for (AboutInfo.DataSource d : AboutInfo.DATA) {
            sb.append("<tr><td valign='top'>").append(escape(d.name()));
            if (!d.version().isEmpty())
                sb.append(' ').append(escape(d.version()));
            sb.append("</td><td valign='top'>&nbsp;&nbsp;<a href=\"").append(d.url()).append("\">").append(escape(d.publisher()))
                    .append("</a></td></tr>");
        }
        sb.append("</table><p>").append(escape(I18n.t("about.en16931"))).append("</p>");
        return sb.append("</body></html>").toString();
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ex) {
            Log.error("Failed to open %s: %s", url, ex.getMessage());
        }
    }

    private static JPanel licenseRow(JDialog dialog) {
        // No gaps at the border, so the row aligns with the other lines.
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel text = new JLabel(I18n.t("about.license", AboutInfo.LICENSE_NAME));
        text.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        p.add(text);
        JButton show = new JButton(I18n.t("about.licenseButton"));
        show.setToolTipText(I18n.t("about.licenseTip"));
        show.addActionListener(e -> showLicense(dialog));
        p.add(show);
        return p;
    }

    /**
     * A text that opens the address in the browser, if the system supports that.
     */
    private static JLabel link(String url) {
        boolean browse = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        JLabel l = new JLabel(browse ? "<html><a href=\"" + url + "\">" + url + "</a></html>" : url, SwingConstants.LEFT);
        if (browse) {
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    browse(url);
                }
            });
        }
        return l;
    }

    private static void showLicense(JDialog owner) {
        String text = AboutInfo.licenseText();
        JTextArea area = new JTextArea(text.isEmpty() ? AboutInfo.LICENSE_NAME + "\n" + AboutInfo.LICENSE_URL : text, 18, 70);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(owner, new JScrollPane(area), AboutInfo.LICENSE_NAME, JOptionPane.PLAIN_MESSAGE);
    }
}
