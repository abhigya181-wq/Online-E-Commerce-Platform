import javax.swing.*;
import java.awt.*;

public class LoginFrame extends JFrame {
    private UserDAO userDAO = new UserDAO();
    private JTextField nameField = new JTextField();
    private JTextField emailField = new JTextField();
    private JPasswordField passField = new JPasswordField();
    private JComboBox<String> roleBox = new JComboBox<>(new String[]{"BUYER", "SELLER"});

    public LoginFrame() {
        setTitle("Online E-Commerce Platform - Login & Register");
        setSize(440, 320);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new GridLayout(6, 2, 8, 8));

        add(new JLabel(" Name (register only):"));
        add(nameField);

        add(new JLabel(" Email:"));
        add(emailField);

        add(new JLabel(" Password:"));
        add(passField);

        add(new JLabel(" Role (register only):"));
        add(roleBox);

        // Login Action (Demonstrates Polymorphism via u.openDashboard())
        add(Util.button("Login", this, () -> {
            String email = emailField.getText().trim();
            String pass = new String(passField.getPassword()).trim();
            if (email.isEmpty() || pass.isEmpty()) {
                throw new Exception("Please enter both email and password.");
            }
            User u = userDAO.login(email, pass);
            if (u == null) {
                throw new Exception("Wrong email or password.");
            }
            dispose();
            // Polymorphism: dynamically dispatches to AdminFrame, SellerFrame, or BuyerFrame
            u.openDashboard();
        }));

        // Registration Action
        add(Util.button("Register", this, () -> {
            String name = nameField.getText().trim();
            String email = emailField.getText().trim();
            String pass = new String(passField.getPassword()).trim();
            if (name.isEmpty() || email.isEmpty() || pass.isEmpty()) {
                throw new Exception("All registration fields (Name, Email, Password) are required.");
            }
            userDAO.add(User.create((String) roleBox.getSelectedItem(), 0, name, email, pass));
            JOptionPane.showMessageDialog(this, "Registered successfully! Now click Login.", "Success", JOptionPane.INFORMATION_MESSAGE);
        }));

        // Quick login reminders for convenience
        add(new JLabel(" Admin: admin@shop.com / admin123"));
        add(new JLabel(" Seller: seller@shop.com / seller123"));
    }
}
