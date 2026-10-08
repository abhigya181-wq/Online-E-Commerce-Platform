import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

// Helper utilities to streamline Swing GUI construction and exception handling
public class Util {

    // Functional interface for lambda-based actions
    public interface Task {
        void run() throws Exception;
    }

    // Executes a task and automatically handles exceptions with user-friendly popups
    public static void run(Component parent, Task task) {
        try {
            task.run();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // Creates a JButton wired to execute a lambda task with automatic error trapping
    public static JButton button(String text, Component parent, Task task) {
        JButton b = new JButton(text);
        b.addActionListener(e -> run(parent, task));
        return b;
    }

    // Creates a JTable with read-only cell editing behavior
    public static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setDefaultEditor(Object.class, null); // cells cannot be edited directly
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return t;
    }

    // Retrieves the ID (column 0) of the selected row, throwing an exception if none is selected
    public static int selectedId(JTable t) throws Exception {
        int row = t.getSelectedRow();
        if (row < 0) {
            throw new Exception("Please select a row first from the table.");
        }
        return (int) t.getValueAt(row, 0);
    }

    // Packages a table with a scroll pane and a bottom control toolbar
    public static JPanel panel(JTable table, JComponent... bottom) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        if (bottom.length > 0) {
            JPanel bar = new JPanel();
            for (JComponent c : bottom) {
                bar.add(c);
            }
            p.add(bar, BorderLayout.SOUTH);
        }
        return p;
    }
}
