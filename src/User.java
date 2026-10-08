// Abstract parent class (Rubric: OOP Abstraction, Inheritance, Polymorphism)
public abstract class User {
    private int id;
    private String name, email, password;

    public User(int id, String name, String email, String password) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }

    // Polymorphic abstract methods: each child class provides specialized behavior
    public abstract String getRole();
    public abstract void openDashboard();

    // Factory method to instantiate correct child type based on role
    public static User create(String role, int id, String name, String email, String password) {
        if ("ADMIN".equalsIgnoreCase(role))  return new Admin(id, name, email, password);
        if ("SELLER".equalsIgnoreCase(role)) return new Seller(id, name, email, password);
        return new Buyer(id, name, email, password);
    }
}

// Child Class 1: Admin
class Admin extends User {
    Admin(int id, String n, String e, String p) { super(id, n, e, p); }
    @Override
    public String getRole() { return "ADMIN"; }
    @Override
    public void openDashboard() { new AdminFrame(this).setVisible(true); }
}

// Child Class 2: Seller
class Seller extends User {
    Seller(int id, String n, String e, String p) { super(id, n, e, p); }
    @Override
    public String getRole() { return "SELLER"; }
    @Override
    public void openDashboard() { new SellerFrame(this).setVisible(true); }
}

// Child Class 3: Buyer
class Buyer extends User {
    Buyer(int id, String n, String e, String p) { super(id, n, e, p); }
    @Override
    public String getRole() { return "BUYER"; }
    @Override
    public void openDashboard() { new BuyerFrame(this).setVisible(true); }
}
