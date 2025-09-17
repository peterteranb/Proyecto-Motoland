package org.example.productos;

import com.google.gson.Gson;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// Modelo para representar un producto de tu inventario
class ProductoInventario {
    private int id;
    private String codigo;
    private String nombre;
    private double precioVenta;
    private int stock;

    public ProductoInventario(int id, String codigo, String nombre, double precioVenta, int stock) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.precioVenta = precioVenta;
        this.stock = stock;
    }
}

@WebServlet("/productos")
public class ProductosServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // ====> ¡CORRECCIÓN AÑADIDA AQUÍ! <====
        // Definimos el tipo de contenido y la codificación al principio del método.
        // Así, cualquier respuesta (sea de éxito o de error) usará UTF-8.
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<ProductoInventario> inventario = new ArrayList<>();
        String sql = "SELECT id, codigo, nombre, precio_venta, stock FROM productos ORDER BY nombre ASC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                inventario.add(new ProductoInventario(
                        rs.getInt("id"),
                        rs.getString("codigo"),
                        rs.getString("nombre"),
                        rs.getDouble("precio_venta"),
                        rs.getInt("stock")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            // Ahora esta respuesta de error también usará UTF-8.
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al conectar con la base de datos.\"}");
            return;
        }

        // Nos aseguramos de enviar un JSON limpio y perfecto
        response.getWriter().write(gson.toJson(inventario));
    }
}