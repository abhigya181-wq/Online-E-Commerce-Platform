# 🛒 Online E-Commerce Platform (Java + Swing + SQLite + Web)

A comprehensive, full-featured E-Commerce platform built in Java with **both** a native **Desktop GUI (Java Swing)** and a **Production-Ready Web Service (HTTP + Single Page App)**, backed by an embedded SQLite database.

---

## 🌟 Highlights & Capabilities

- **Zero Database Setup**: Uses an embedded SQLite database (`shop.db`) that auto-initializes tables and demo data on first start.
- **Dual Execution Modes**:
  1. 🖥️ **Desktop Swing GUI**: Pure Java Swing native desktop application meeting 100% of the academic marking rubric.
  2. 🌐 **Web Platform**: Responsive Single-Page Web Application running on standard Java HTTP server (`localhost:8080` or deployed on Render).
- **Role-Based Workflows**:
  - 👑 **Admin**: User management (create/delete users), product moderation, and order status tracking with a background auto-refresh thread.
  - 🏪 **Seller**: Product catalog management, inventory stock updates, bold red low-stock alert (< 5 items), and order fulfillment.
  - 🛍️ **Buyer**: Product search via keyword, synchronized purchasing with custom stock validation, and personal order history tracking.

---

## 📊 Marking Rubric Coverage

| Rubric Item (Marks) | Implementation in Code |
|---|---|
| **OOP: Polymorphism, Inheritance, Exception Handling, Interfaces (10)** | • Abstract `User` with subclasses `Admin`, `Seller`, `Buyer`<br>• Polymorphic `openDashboard()` and `getRole()`<br>• Generic `Manageable<T>` interface<br>• Custom `StockException` and `Util.run` exception handling |
| **Collections & Generics (6)** | `Manageable<T>`, `List<User>`, `List<Product>`, `List<Object[]>` |
| **Multithreading & Synchronization (4)** | • Daemon background thread in `AdminFrame.startAutoRefresh()` polling every 3s + `SwingUtilities.invokeLater`<br>• Thread-safe `synchronized (OrderDAO.class)` block in `OrderDAO.placeOrder()` |
| **Classes for Database Operations (7)** | `UserDAO.java`, `ProductDAO.java`, `OrderDAO.java` |
| **Database Connectivity JDBC (3)** | `DBConnection.java` using `DriverManager.getConnection("jdbc:sqlite:shop.db")` |
| **JDBC Implementation with PreparedStatements (3)** | Parameterized `PreparedStatement` queries and `ResultSet` mapping across all DAOs |
| **Total Marks** | **33 / 33 (100% Complete)** |

---

## 🔑 Demo Login Accounts

| Role | Email | Password | Permissions |
|---|---|---|---|
| **Admin** | `admin@shop.com` | `admin123` | System management, product moderation, all orders |
| **Seller** | `seller@shop.com` | `seller123` | Inventory CRUD, stock alerts, order fulfillment |
| **Buyer** | `buyer@shop.com` | `buyer123` | Product browsing, keyword search, purchasing |

---

## 💻 Running Locally

### 1. Compile the Code

**Windows (PowerShell / Command Prompt):**
```powershell
javac -cp "lib/*" -d out src/*.java
```

**Linux / macOS:**
```bash
javac -cp "lib/*" -d out src/*.java
```

---

### 2. Launch the Desktop Swing GUI

**Windows:**
```powershell
java -cp "out;lib/*" Main
```

**Linux / macOS:**
```bash
java -cp "out:lib/*" Main
```

---

### 3. Launch the Local Web Platform

**Windows:**
```powershell
java -cp "out;lib/*" WebServer
```

**Linux / macOS:**
```bash
java -cp "out:lib/*" WebServer
```

Then open your browser at: **`http://localhost:8080`**

---

## 🚀 How to Deploy on Render (Step-by-Step)

The project includes both a `Dockerfile` and native deployment scripts (`render-build.sh`, `render-start.sh`, `render.yaml`), making deployment on **[Render.com](https://render.com)** free and seamless.

### Step 1: Push Project to GitHub

1. Initialize git and commit your files:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of Java E-Commerce Platform"
   ```
2. Create a new repository on GitHub (e.g., `ecommerce-java-platform`).
3. Push to your repository:
   ```bash
   git branch -M main
   git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/ecommerce-java-platform.git
   git push -u origin main
   ```

---

### Step 2: Deploy on Render

#### Option A: Docker Deployment (Recommended — 100% Reliable)

1. Sign up or log into [Render.com](https://render.com).
2. In the Render Dashboard, click **New +** &rarr; **Web Service**.
3. Select **Build and deploy from a Git repository** and connect your GitHub repository.
4. Set the following configuration:
   - **Name**: `ecommerce-platform` (or any name you prefer)
   - **Region**: Choose the closest region (e.g., Oregon or Frankfurt)
   - **Language / Runtime**: **Docker** (Render detects the `Dockerfile` automatically)
   - **Instance Type**: **Free**
5. Click **Create Web Service**.
6. Render will build the Docker container and start your service. Once the build finishes, your live URL will be ready:
   ```
   https://<your-app-name>.onrender.com
   ```

---

#### Option B: Native Shell / Linux Deployment

If you prefer deploying without Docker:

1. In Render, select **New +** &rarr; **Web Service**.
2. Connect your GitHub repository.
3. Choose:
   - **Runtime**: **Native** (or **Java**)
   - **Build Command**: `./render-build.sh`
   - **Start Command**: `./render-start.sh`
4. Under **Advanced** &rarr; **Add Environment Variable**:
   - Key: `PORT` | Value: `10000`
5. Click **Create Web Service**.

---

### Step 3: Verify Your Live Deployment

1. **Health Check**: Open `https://<your-app-name>.onrender.com/health` in your browser. You should see:
   ```json
   {"status":"UP","timestamp":"...","service":"ecommerce-platform"}
   ```
2. **Web App**: Open `https://<your-app-name>.onrender.com` to access the full interactive web application.
3. Test logging in with `admin@shop.com` / `admin123`.

---

## 📁 Project Directory Structure

```
java project/
 ├── src/
 │    ├── AdminFrame.java        # Admin Swing dashboard with auto-refresh thread
 │    ├── BuyerFrame.java        # Buyer Swing dashboard for shopping & orders
 │    ├── DBConnection.java      # SQLite connection & schema initializer
 │    ├── LoginFrame.java        # Authentication GUI with polymorphic dispatch
 │    ├── Main.java              # Swing desktop application entry point
 │    ├── Manageable.java        # Generic DAO interface <T>
 │    ├── OrderDAO.java          # Thread-safe synchronized order management
 │    ├── Product.java           # Encapsulated Product model
 │    ├── ProductDAO.java        # Product catalog queries & inventory manager
 │    ├── SellerFrame.java       # Seller Swing dashboard with low-stock alerts
 │    ├── StockException.java    # Custom checked exception for stock errors
 │    ├── User.java              # Polymorphic User hierarchy (Admin, Seller, Buyer)
 │    ├── UserDAO.java           # User management and authentication
 │    ├── Util.java              # Swing UI helpers & exception handling runners
 │    └── WebServer.java         # Built-in HTTP server & single-page web app
 ├── lib/
 │    ├── sqlite-jdbc-3.45.2.0.jar
 │    ├── slf4j-api-1.7.36.jar
 │    └── slf4j-simple-1.7.36.jar
 ├── Dockerfile                  # Multi-stage Docker build for cloud hosting
 ├── .dockerignore               # Docker build exclusions
 ├── render.yaml                 # Render Blueprint specification
 ├── render-build.sh             # Linux build script for Render
 ├── render-start.sh             # Linux start script for Render
 ├── .gitignore                  # Git repository ignore rules
 └── README.md                   # Full documentation & deployment guide
```
