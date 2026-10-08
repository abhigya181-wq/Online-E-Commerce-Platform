import java.sql.SQLException;
import java.util.List;

// Interface + Generics: defines common database operations (Rubric: Interfaces & Generics)
public interface Manageable<T> {
    void add(T item) throws SQLException;
    void delete(int id) throws SQLException;
    List<T> getAll() throws SQLException;
}
