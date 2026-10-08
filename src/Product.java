// Product entity model (Rubric: OOP Encapsulation)
public class Product {
    private int id;
    private int sellerId;
    private String name;
    private double price;
    private int stock;

    public Product(int id, int sellerId, String name, double price, int stock) {
        this.id = id;
        this.sellerId = sellerId;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    // Encapsulation: Getter methods
    public int getId() { return id; }
    public int getSellerId() { return sellerId; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getStock() { return stock; }
}
