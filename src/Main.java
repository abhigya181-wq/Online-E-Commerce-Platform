import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        // 1. Initialize SQLite database schema and seed default users
        DBConnection.createTables();

        // 2. Launch GUI safely on the Event Dispatch Thread (Swing best practice)
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
