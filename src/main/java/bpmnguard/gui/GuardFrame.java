package bpmnguard.gui;

import bpmnguard.model.Bounds;
import bpmnguard.render.RenderOptions;
import bpmnguard.render.Transform;
import bpmnguard.render.ViolationOverlay;
import bpmnguard.report.Json;
import bpmnguard.report.Report;
import bpmnguard.report.Validator;
import bpmnguard.rules.RuleOptions;
import bpmnguard.rules.Violation;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Interactive desktop front-end.
 *
 * Toolbar (Open / Export PNG / zoom) over a vertical split: the scrollable annotated
 * diagram on top, and below it the findings table beside a detail pane. Selecting a
 * row scrolls the diagram to the element; clicking a highlighted element or loop
 * outline on the diagram selects its row.
 */
public final class GuardFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"Rule", "Severity", "Element ID", "Element Name", "Type", "Finding", "Remediation", "#"};
    private static final int COL_INDEX = 7;
    private static final int[] WRAPPED = {5, 6};
    private static final double[] ZOOM = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0};

    private final transient Preferences prefs = Preferences.userRoot().node("bpmn-guard");

    private transient Report report;
    private transient BufferedImage image;
    private double scale = 1.5;
    private transient List<ViolationOverlay.HitRegion> regions = List.of();

    private final JPanel canvas;
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextArea detail;
    private final JLabel status;

    public GuardFrame() {
        super("bpmn-guard");

        canvas = new JPanel() {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() {
                return image == null ? new Dimension(900, 500) : new Dimension(image.getWidth(), image.getHeight());
            }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (image != null) g2.drawImage(image, 0, 0, null);
                else {
                    g2.setColor(new Color(120, 120, 120));
                    g2.drawString("Open a .bpmn file to validate and render it.", 30, getHeight() / 2);
                }
            }
        };
        canvas.setBackground(Color.WHITE);

        model = new DefaultTableModel(COLUMNS, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == COL_INDEX ? Integer.class : String.class; }
        };
        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFillsViewportHeight(true);

        detail = new JTextArea("Select a finding to see the full assessment.");
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setMargin(new Insets(8, 10, 8, 10));
        detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

        status = new JLabel("No file loaded.");
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        configureColumns();
        wireTable();
        wireCanvas();

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(button("Open .BPMN file…", this::open));
        toolbar.add(button("Export annotated PNG…", this::export));
        toolbar.addSeparator();
        toolbar.add(button("Zoom −", () -> zoom(-1)));
        toolbar.add(button("Zoom +", () -> zoom(1)));
        toolbar.addSeparator();
        toolbar.add(new JLabel("  Red: OOM/crash   Orange: deadlock/orphaned tokens   Amber: transaction starvation"));

        JSplitPane lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        lower.setResizeWeight(0.6);
        lower.setOneTouchExpandable(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(canvas), lower);
        split.setResizeWeight(0.55);
        split.setOneTouchExpandable(true);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1440, 900);
        setLocationRelativeTo(null);
    }

    // -----------------------------------------------------------------------
    // Table
    // -----------------------------------------------------------------------

    private void configureColumns() {
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setMaxWidth(60);
        cm.getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                Violation viol = violationAtViewRow(row);
                if (!sel && viol != null) c.setForeground(ViolationOverlay.color(viol.rule));
                c.setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });
        cm.getColumn(1).setMaxWidth(90);
        cm.getColumn(2).setPreferredWidth(150);
        cm.getColumn(3).setPreferredWidth(170);
        cm.getColumn(4).setPreferredWidth(110);
        cm.getColumn(5).setPreferredWidth(520);
        cm.getColumn(6).setPreferredWidth(420);
        TableCellRenderer wrap = new WrappingRenderer();
        cm.getColumn(5).setCellRenderer(wrap);
        cm.getColumn(6).setCellRenderer(wrap);
        cm.removeColumn(cm.getColumn(COL_INDEX)); // hidden; keeps row -> violation mapping
    }

    /** Word-wrapping cell; row heights are recomputed from it (see adjustRowHeights). */
    private static final class WrappingRenderer extends JTextArea implements TableCellRenderer {
        private static final long serialVersionUID = 1L;
        WrappingRenderer() {
            setLineWrap(true); setWrapStyleWord(true); setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            setText(v == null ? "" : v.toString());
            setFont(t.getFont());
            setBackground(sel ? t.getSelectionBackground() : t.getBackground());
            setForeground(sel ? t.getSelectionForeground() : t.getForeground());
            return this;
        }
    }

    private void adjustRowHeights() {
        for (int row = 0; row < table.getRowCount(); row++) {
            int h = 22;
            for (int col : WRAPPED) {
                int vc = table.convertColumnIndexToView(col);
                if (vc < 0) continue;
                Component c = table.prepareRenderer(table.getCellRenderer(row, vc), row, vc);
                c.setSize(table.getColumnModel().getColumn(vc).getWidth(), 1000);
                h = Math.max(h, c.getPreferredSize().height);
            }
            if (table.getRowHeight(row) != h) table.setRowHeight(row, h);
        }
    }

    private void wireTable() {
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
            @Override public void columnMarginChanged(javax.swing.event.ChangeEvent e) { adjustRowHeights(); }
            @Override public void columnAdded(javax.swing.event.TableColumnModelEvent e) { }
            @Override public void columnRemoved(javax.swing.event.TableColumnModelEvent e) { }
            @Override public void columnMoved(javax.swing.event.TableColumnModelEvent e) { }
            @Override public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) { }
        });
        model.addTableModelListener(e -> SwingUtilities.invokeLater(this::adjustRowHeights));
        table.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { adjustRowHeights(); }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Violation v = selectedViolation();
            detail.setText(detailText(v));
            detail.setCaretPosition(0);
            scrollCanvasTo(v);
        });
    }

    private Violation violationAtViewRow(int viewRow) {
        if (report == null || viewRow < 0) return null;
        int mrow = table.convertRowIndexToModel(viewRow);
        int idx = (Integer) model.getValueAt(mrow, COL_INDEX);
        return report.violations().get(idx);
    }

    private Violation selectedViolation() { return violationAtViewRow(table.getSelectedRow()); }

    private void fillTable() {
        model.setRowCount(0);
        List<Violation> vs = report.violations();
        for (int i = 0; i < vs.size(); i++) {
            Violation v = vs.get(i);
            model.addRow(new Object[]{v.rule.name(), v.severity.name(), v.elementId,
                    v.elementName == null ? "" : v.elementName, v.elementType == null ? "" : v.elementType,
                    kindLabel(v) + v.message, v.remediation, i});
        }
    }

    private static String kindLabel(Violation v) { return v.kind == null ? "" : "[" + v.kind + "] "; }

    private static String detailText(Violation v) {
        if (v == null) return "Select a finding to see the full assessment.";
        StringBuilder sb = new StringBuilder();
        sb.append(v.rule).append(' ').append(kindLabel(v)).append("· ").append(v.severity).append(" · ").append(v.elementId);
        if (v.elementName != null) sb.append(" — ").append(v.elementName);
        sb.append('\n').append(v.elementType == null ? "" : v.elementType).append(" in scope ").append(v.scope == null ? "process" : v.scope).append("\n\n");
        sb.append("FINDING\n").append(v.message).append("\n\n");
        sb.append("REMEDIATION\n").append(v.remediation).append("\n\n");
        if (!v.related.isEmpty()) sb.append("RELATED ELEMENTS\n").append(String.join(", ", v.related)).append("\n\n");
        sb.append("EVIDENCE\n").append(Json.write(v.evidence, true));
        return sb.toString();
    }

    private void selectViolation(int idx) {
        for (int mrow = 0; mrow < model.getRowCount(); mrow++) {
            if ((Integer) model.getValueAt(mrow, COL_INDEX) != idx) continue;
            int vrow = table.convertRowIndexToView(mrow);
            if (vrow >= 0) {
                table.setRowSelectionInterval(vrow, vrow);
                table.scrollRectToVisible(table.getCellRect(vrow, 0, true));
            }
            return;
        }
    }

    // -----------------------------------------------------------------------
    // Canvas
    // -----------------------------------------------------------------------

    private RenderOptions renderOptions() { return RenderOptions.DEFAULT.withScale(scale); }

    private void wireCanvas() {
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int idx = ViolationOverlay.hitTest(regions, e.getX(), e.getY());
                if (idx >= 0) selectViolation(idx);
            }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                boolean hit = ViolationOverlay.hitTest(regions, e.getX(), e.getY()) >= 0;
                canvas.setCursor(hit ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
    }

    private void renderCurrent() {
        if (report == null) return;
        if (report.diagram().isEmpty()) {
            image = null; regions = List.of();
        } else {
            image = ViolationOverlay.renderReport(report, renderOptions());
            regions = ViolationOverlay.hitRegions(report, renderOptions());
        }
        canvas.revalidate();
        canvas.repaint();
    }

    private void scrollCanvasTo(Violation v) {
        if (v == null || v.bounds == null || report == null || report.diagram().isEmpty()) return;
        Bounds b = v.bounds;
        Transform t = new Transform(report.diagram(), renderOptions());
        int pad = 80;
        canvas.scrollRectToVisible(new Rectangle((int) (t.x(b.x()) - pad), (int) (t.y(b.y()) - pad),
                (int) (t.s(b.width()) + 2 * pad), (int) (t.s(b.height()) + 2 * pad)));
    }

    private void zoom(int dir) {
        int idx = 0;
        for (int i = 0; i < ZOOM.length; i++) if (ZOOM[i] == scale) idx = i;
        scale = ZOOM[Math.max(0, Math.min(ZOOM.length - 1, idx + dir))];
        renderCurrent();
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    public void load(Path file) {
        status.setText("Validating " + file + " …");
        Thread worker = new Thread(() -> {
            try {
                Report r = Validator.validate(file, RuleOptions.DEFAULT);
                SwingUtilities.invokeLater(() -> {
                    report = r;
                    fillTable();
                    status.setText(summaryText(r));
                    renderCurrent();
                    setTitle("bpmn-guard — " + file.getFileName());
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("Failed: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Cannot validate file", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "bpmn-guard-validate");
        worker.setDaemon(true);
        worker.start();
    }

    private static String summaryText(Report r) {
        String s = String.format("%s  —  %d critical, %d high, %d medium", r.source(),
                r.count(bpmnguard.rules.Severity.CRITICAL), r.count(bpmnguard.rules.Severity.HIGH), r.count(bpmnguard.rules.Severity.MEDIUM));
        return r.warnings().isEmpty() ? s : s + "   |   " + String.join(" · ", r.warnings());
    }

    private void open() {
        File f = chooseFile(true);
        if (f != null) load(f.toPath());
    }

    private void export() {
        if (image == null) {
            JOptionPane.showMessageDialog(this, "Nothing rendered yet.", "Export", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File f = chooseFile(false);
        if (f == null) return;
        try {
            ViolationOverlay.writePng(image, f.toPath());
            JOptionPane.showMessageDialog(this, "Saved " + f.getPath(), "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // File chooser
    //
    // Swing's chooser starts in user.home, which under WSL / a container is the
    // Linux home, not the Windows profile the person sees in Explorer. Resolve, in
    // order: last directory used, $BPMN_GUARD_DIR, the Windows profile under WSL,
    // user.home.
    // -----------------------------------------------------------------------

    private File chooseFile(boolean open) {
        JFileChooser fc = new JFileChooser(startDir());
        fc.setFileHidingEnabled(false);
        fc.setFileFilter(open
                ? new FileNameExtensionFilter("BPMN files (*.bpmn, *.xml)", "bpmn", "xml")
                : new FileNameExtensionFilter("PNG image (*.png)", "png"));
        int res = open ? fc.showOpenDialog(this) : fc.showSaveDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        File dir = f.isDirectory() ? f : f.getParentFile();
        if (dir != null) prefs.put("last-dir", dir.getAbsolutePath());
        if (!open && !f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) f = new File(f.getPath() + ".png");
        return f;
    }

    private File startDir() {
        File f = existingDir(prefs.get("last-dir", null));
        if (f == null) f = existingDir(System.getenv("BPMN_GUARD_DIR"));
        if (f == null && (System.getenv("WSL_DISTRO_NAME") != null || new File("/mnt/c/Users").isDirectory())) {
            String user = System.getenv("USER") != null ? System.getenv("USER") : System.getProperty("user.name");
            f = existingDir("/mnt/c/Users/" + user);
            if (f == null) f = existingDir("/mnt/c/Users");
        }
        if (f == null) f = existingDir(System.getProperty("user.home"));
        return f == null ? new File(".") : f;
    }

    private static File existingDir(String path) {
        if (path == null || path.isBlank()) return null;
        File f = new File(path);
        return f.isDirectory() ? f : null;
    }

    // -----------------------------------------------------------------------

    /** Show the window on the EDT; validate {@code file} immediately when given. */
    public static void launch(Path file) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> {
            GuardFrame f = new GuardFrame();
            f.setVisible(true);
            if (file != null) f.load(file);
        });
    }
}
