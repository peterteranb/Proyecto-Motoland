package org.example.ventas;

import org.example.DatabaseConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ProductoVenta {
    private int id;
    private String codigo;
    private String nombre;
    private double precioVenta;
    private int stock;
    private String ubicacion;

    public ProductoVenta(int id, String codigo, String nombre, double precioVenta, int stock, String ubicacion) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.precioVenta = precioVenta;
        this.stock = stock;
        this.ubicacion = ubicacion;
    }
    // getters si los necesitas
}

class VentaItem {
    Integer productoId;
    Integer cantidad;
    Double precioUnitario;
}

@WebServlet("/ventas")
public class VentasServlet extends HttpServlet {
    private final Gson gson = new Gson();

    /**
     * GET: devuelve la lista de productos (para autocompletar).
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<ProductoVenta> productos = new ArrayList<>();
        String sql = "SELECT id, codigo, nombre, precio_venta, stock, ubicacion FROM productos";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                productos.add(new ProductoVenta(
                        rs.getInt("id"),
                        rs.getString("codigo"),
                        rs.getString("nombre"),
                        rs.getDouble("precio_venta"),
                        rs.getInt("stock"),
                        rs.getString("ubicacion")
                ));
            }
            response.getWriter().write(gson.toJson(productos));
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"message\":\"Error al obtener productos.\"}");
        }
    }

    /**
     * POST: crear una nueva venta. Se espera JSON con:
     * { clienteId: <int|null>, items: [{productoId, cantidad, precioUnitario}, ...] }
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> payload = gson.fromJson(request.getReader(), mapType);

        Integer clienteId = null;
        if (payload.get("clienteId") != null) {
            try {
                clienteId = ((Double) payload.get("clienteId")).intValue();
            } catch (ClassCastException ex) {
                // si viene como Integer
                clienteId = ((Number) payload.get("clienteId")).intValue();
            }
        }

        Type itemsType = new TypeToken<List<VentaItem>>(){}.getType();
        List<VentaItem> items = gson.fromJson(gson.toJson(payload.get("items")), itemsType);

        if (items == null || items.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"La venta debe contener al menos 1 item.\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // 1) validar stock
            String stockSql = "SELECT stock, nombre FROM productos WHERE id = ?";
            try (PreparedStatement psStock = conn.prepareStatement(stockSql)) {
                for (VentaItem it : items) {
                    psStock.setInt(1, it.productoId);
                    try (ResultSet rs = psStock.executeQuery()) {
                        if (rs.next()) {
                            int stock = rs.getInt("stock");
                            if (it.cantidad > stock) {
                                throw new SQLException("Stock insuficiente para producto: " + rs.getString("nombre") + ". Stock actual: " + stock);
                            }
                        } else {
                            throw new SQLException("Producto no encontrado ID: " + it.productoId);
                        }
                    }
                }
            }

            // 2) calcular total
            double totalVenta = items.stream().mapToDouble(it -> (it.cantidad * (it.precioUnitario == null ? 0.0 : it.precioUnitario))).sum();

            // 3) insertar venta (con cliente si viene)
            String insertVenta = "INSERT INTO ventas (cliente_id, total) VALUES (?, ?)";
            long ventaId;
            try (PreparedStatement psIns = conn.prepareStatement(insertVenta, Statement.RETURN_GENERATED_KEYS)) {
                if (clienteId == null) psIns.setNull(1, Types.INTEGER);
                else psIns.setInt(1, clienteId);
                psIns.setDouble(2, totalVenta);
                psIns.executeUpdate();
                try (ResultSet gk = psIns.getGeneratedKeys()) {
                    if (gk.next()) ventaId = gk.getLong(1);
                    else throw new SQLException("No se pudo generar ID de venta.");
                }
            }

            // 4) generar codigo_venta y actualizar
            String codigoVenta = "VENTA-" + ventaId;
            try (PreparedStatement psUpd = conn.prepareStatement("UPDATE ventas SET codigo_venta = ? WHERE id = ?")) {
                psUpd.setString(1, codigoVenta);
                psUpd.setLong(2, ventaId);
                psUpd.executeUpdate();
            }

            // 5) insertar detalle y restar stock
            String insertDetalle = "INSERT INTO detalle_ventas (venta_id, producto_id, cantidad, precio_unitario, subtotal) VALUES (?, ?, ?, ?, ?)";
            String updateStock = "UPDATE productos SET stock = stock - ? WHERE id = ?";
            try (PreparedStatement psDet = conn.prepareStatement(insertDetalle);
                 PreparedStatement psUpdStock = conn.prepareStatement(updateStock)) {

                for (VentaItem it : items) {
                    double precio = (it.precioUnitario == null ? 0.0 : it.precioUnitario);
                    double subtotal = it.cantidad * precio;

                    psDet.setLong(1, ventaId);
                    psDet.setInt(2, it.productoId);
                    psDet.setInt(3, it.cantidad);
                    psDet.setDouble(4, precio);
                    psDet.setDouble(5, subtotal);
                    psDet.addBatch();

                    psUpdStock.setInt(1, it.cantidad);
                    psUpdStock.setInt(2, it.productoId);
                    psUpdStock.addBatch();
                }
                psDet.executeBatch();
                psUpdStock.executeBatch();
            }

            conn.commit();
            response.getWriter().write("{\"status\":\"success\",\"message\":\"Venta " + codigoVenta + " registrada correctamente.\"}");
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"","'") + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}
