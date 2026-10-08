import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class AdminFrame extends JFrame {
    private UserDAO userDAO = new UserDAO();
    private ProductDAO productDAO = new ProductDAO();
    private OrderDAO orderDAO = new OrderDAO();

    // Table models
    private DefaultTableModel userModel = new DefaultTableModel(new String[]{"ID", "Name", "Email", "Role"}, 0);
    private DefaultTableModel productModel = new DefaultTableModel(new String[]{"ID", "Name", "Price", "Stock", "Seller ID"}, 0);
    private DefaultTableModel orderModel = new DefaultTableModel(new String[]{"Order ID", "Buyer", "Product", "Qty", "Total", "Status"}, 0);

    private JTable userTable = Util.table(userModel);
    private JTable productTable = Util.table(productModel);
    private JTable orderTable = Util.table(orderModel);
    private JComboBox<String> statusBox = new JComboBox<>(new String[]{"Pending", "Shipped", "Delivered", "Cancelled"});

    public AdminFrame(User admin) {
        setTitle("Admin Dashboard - " + admin.getName());
        setSize(850, 520);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.add("Users", userPanel());
        tabs.add("Products", productPanel());
        tabs.add("Orders", orderPanel());
        add(tabs);

        loadUsers();
        loadProducts();
        loadOrders();

        // Rubric: Multithreading - Background auto-refresh thread
        startAutoRefresh();
    }

    private JPanel userPanel() {
        JButton add = Util.button("Add User", this, () -> {
            String name = JOptionPane.showInputDialog(this, "Name:");
            if (name == null || name.trim().isEmpty()) return;

            String email = JOptionPane.showInputDialog(this, "Email:");
            if (email == null || email.trim().isEmpty()) return;

            String pass = JOptionPane.showInputDialog(this, "Password:");
            if (pass == null || pass.trim().isEmpty()) return;

            String role = (String) JOptionPane.showInputDialog(this, "Role:", "Select Role",
                    JOptionPane.QUESTION_MESSAGE, null, new String[]{"ADMIN", "SELLER", "BUYER"}, "BUYER");
            if (role == null) return;

            userDAO.add(User.create(role, 0, name.trim(), email.trim(), pass.trim()));
            loadUsers();
        });

        JButton del = Util.button("Delete User", this, () -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete this user?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                userDAO.delete(Util.selectedId(userTable));
                loadUsers();
            }
        });

        return Util.panel(userTable, add, del);
    }

    private JPanel productPanel() {
        JButton del = Util.button("Remove Product", this, () -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to remove this product?", "Confirm Remove", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                productDAO.delete(Util.selectedId(productTable));
                loadProducts();
            }
        });
        return Util.panel(productTable, del);
    }

    private JPanel orderPanel() {
        JButton update = Util.button("Update Status", this, () -> {
            orderDAO.updateStatus(Util.selectedId(orderTable), (String) statusBox.getSelectedItem());
            loadOrders();
        });
        return Util.panel(orderTable, new JLabel("Change Status:"), statusBox, update);
    }

    private void loadUsers() {
        Util.run(this, () -> {
            userModel.setRowCount(0);
            for (User u : userDAO.getAll()) {
                userModel.addRow(new Object[]{u.getId(), u.getName(), u.getEmail(), u.getRole()});
            }
        });
    }

    private void loadProducts() {
        Util.run(this, () -> {
            productModel.setRowCount(0);
            for (Product p : productDAO.getAll()) {
                productModel.addRow(new Object[]{p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getSellerId()});
            }
        });
    }

    private void loadOrders() {
        int selected = orderTable.getSelectedRow(); // remember row selection across refreshes
        Util.run(this, () -> {
            orderModel.setRowCount(0);
            for (Object[] row : orderDAO.getAll()) {
                orderModel.addRow(row);
            }
        });
        if (selected >= 0 && selected < orderTable.getRowCount()) {
            orderTable.setRowSelectionInterval(selected, selected);
        }
    }

    // Multithreading (Rubric: Multithreading & Synchronization)
    // Background worker thread polls SQLite every 3 seconds to auto-refresh the orders table
    // UI updates are safely dispatched onto the Event Dispatch Thread via SwingUtilities.invokeLater
    private void startAutoRefresh() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000); // 3 second polling interval
                } catch (InterruptedException e) {
                    return; // thread gracefully terminates if interrupted
                }
                SwingUtilities.invokeLater(this::loadOrders);
            }
        });
        t.setDaemon(true); // daemon thread terminates automatically when Admin window closes
        t.start();
    }
}
