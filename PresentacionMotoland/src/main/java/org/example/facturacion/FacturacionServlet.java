package org.example.facturacion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// --- Modelos de Datos para manejar la información ---

// Para los productos que vienen de la venta original (ahora incluye 'ubicacion')
class ProductoVenta {
    private String nombre;
    private String ubicacion;
    private int cantidad;
    private double precioUnitario;
    private double subtotal;

    public ProductoVenta(String nombre, String ubicacion, int cantidad, double precioUnitario, double subtotal) {
        this.nombre = nombre;
        this.ubicacion = ubicacion;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.subtotal = subtotal;
    }
}

// Para la respuesta del GET que incluye los datos de la venta
class VentaParaFacturar {
    private int ventaId;
    private String codigoVenta;
    private String fechaVenta;
    private String clienteNombre;
    private List<ProductoVenta> productos;

    public VentaParaFacturar(int ventaId, String codigoVenta, String fechaVenta, String clienteNombre, List<ProductoVenta> productos) {
        this.ventaId = ventaId;
        this.codigoVenta = codigoVenta;
        this.fechaVenta = fechaVenta;
        this.clienteNombre = clienteNombre;
        this.productos = productos;
    }
}

// Para recibir los ítems dinámicos del POST
class ItemAdicional {
    String descripcion;
    int cantidad;
    double montoUnitario;
    String tipo; // "ADICION" o "DEDUCCION"
}

@WebServlet("/facturacion")
public class FacturacionServlet extends HttpServlet {
    private final Gson gson = new Gson();

    /**
     * Busca una venta por su código y devuelve sus detalles para iniciar la facturación.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String codigoVenta = request.getParameter("codigoVenta");
        if (codigoVenta == null || codigoVenta.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"message\":\"El código de venta es requerido.\"}");
            return;
        }

        // Permitimos ventas con estado_facturacion NULL o 'Pendiente' (para compatibilidad)
        String sql = "SELECT v.id, v.codigo_venta, v.fecha_venta, c.nombre as cliente_nombre " +
                "FROM ventas v LEFT JOIN clientes c ON v.cliente_id = c.id " +
                "WHERE v.codigo_venta = ? AND (v.estado_facturacion IS NULL OR v.estado_facturacion = 'Pendiente')";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, codigoVenta);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                int ventaId = rs.getInt("id");
                List<ProductoVenta> productos = getProductosDeVenta(conn, ventaId);
                VentaParaFacturar venta = new VentaParaFacturar(
                        ventaId,
                        rs.getString("codigo_venta"),
                        rs.getString("fecha_venta"),
                        rs.getString("cliente_nombre") != null ? rs.getString("cliente_nombre") : "Cliente General",
                        productos
                );
                response.getWriter().write(gson.toJson(venta));
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("{\"message\":\"Venta no encontrada, ya fue facturada o no existe.\"}");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"message\":\"Error en la base de datos.\"}");
        }
    }

    /**
     * Obtiene los productos de una venta, incluyendo la ubicacion (columna productos.ubicacion).
     */
    private List<ProductoVenta> getProductosDeVenta(Connection conn, int ventaId) throws SQLException {
        List<ProductoVenta> productos = new ArrayList<>();
        String sql = "SELECT p.nombre, p.ubicacion, dv.cantidad, dv.precio_unitario, dv.subtotal " +
                "FROM detalle_ventas dv JOIN productos p ON dv.producto_id = p.id " +
                "WHERE dv.venta_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, ventaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nombre = rs.getString("nombre");
                    String ubicacion = rs.getString("ubicacion"); // puede ser NULL
                    int cantidad = rs.getInt("cantidad");
                    double precioUnitario = rs.getDouble("precio_unitario");
                    double subtotal = rs.getDouble("subtotal");
                    productos.add(new ProductoVenta(nombre, ubicacion != null ? ubicacion : "", cantidad, precioUnitario, subtotal));
                }
            }
        }
        return productos;
    }

    /**
     * Recibe los datos de la factura y la guarda en la base de datos.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Connection conn = null;

        try {
            // Leemos el JSON complejo que nos envía el frontend
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> payload = gson.fromJson(request.getReader(), type);

            // conversiones seguras usando Number
            Number ventaIdNum = (Number) payload.get("ventaId");
            if (ventaIdNum == null) throw new IllegalArgumentException("ventaId es requerido");
            int ventaId = ventaIdNum.intValue();

            Number tasaImpuestoNum = (Number) payload.get("tasaImpuesto");
            double tasaImpuesto = (tasaImpuestoNum != null) ? tasaImpuestoNum.doubleValue() : 0.0;

            Number descuentoGlobalNum = (Number) payload.get("descuentoGlobal");
            double descuentoGlobal = (descuentoGlobalNum != null) ? descuentoGlobalNum.doubleValue() : 0.0;

            List<ItemAdicional> itemsAdicionales = gson.fromJson(gson.toJson(payload.get("itemsAdicionales")), new TypeToken<List<ItemAdicional>>(){}.getType());
            if (itemsAdicionales == null) itemsAdicionales = new ArrayList<>();

            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); // ¡Iniciamos transacción!

            // 1. Calculamos totales en el backend para seguridad
            double subtotalProductos = 0;
            String sqlSubtotal = "SELECT IFNULL(SUM(subtotal),0) as total FROM detalle_ventas WHERE venta_id = ?";
            try(PreparedStatement stmt = conn.prepareStatement(sqlSubtotal)) {
                stmt.setInt(1, ventaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if(rs.next()) subtotalProductos = rs.getDouble("total");
                }
            }

            double totalAdicionales = itemsAdicionales.stream()
                    .mapToDouble(item -> {
                        double monto = (double) item.cantidad * item.montoUnitario;
                        return "ADICION".equalsIgnoreCase(item.tipo) ? monto : -monto;
                    }).sum();

            double baseImponible = subtotalProductos + totalAdicionales - descuentoGlobal;
            if (baseImponible < 0) baseImponible = 0;
            double montoImpuestos = baseImponible * (tasaImpuesto / 100.0);
            double totalFinal = baseImponible + montoImpuestos;

            // 2. Insertamos la factura principal
            long facturaId = 0;
            String sqlFactura = "INSERT INTO facturas (venta_id, tasa_impuesto, descuento_global, subtotal_productos, total_adicionales, monto_impuestos, total_final) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sqlFactura, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, ventaId);
                stmt.setDouble(2, tasaImpuesto);
                stmt.setDouble(3, descuentoGlobal);
                stmt.setDouble(4, subtotalProductos);
                stmt.setDouble(5, totalAdicionales);
                stmt.setDouble(6, montoImpuestos);
                stmt.setDouble(7, totalFinal);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if(rs.next()) facturaId = rs.getLong(1);
                }
            }

            // 3. Generamos y actualizamos el código de factura
            String codigoFactura = "FACT-" + facturaId;
            String sqlUpdateCodigo = "UPDATE facturas SET codigo_factura = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdateCodigo)) {
                stmt.setString(1, codigoFactura);
                stmt.setLong(2, facturaId);
                stmt.executeUpdate();
            }

            // 4. Insertamos los ítems adicionales
            if (!itemsAdicionales.isEmpty()) {
                String sqlItems = "INSERT INTO factura_items_adicionales (factura_id, descripcion, cantidad, monto_unitario, tipo) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sqlItems)) {
                    for(ItemAdicional item : itemsAdicionales) {
                        stmt.setLong(1, facturaId);
                        stmt.setString(2, item.descripcion);
                        stmt.setInt(3, item.cantidad);
                        stmt.setDouble(4, item.montoUnitario);
                        stmt.setString(5, item.tipo);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            // 5. Actualizamos el estado de la venta
            String sqlUpdateVenta = "UPDATE ventas SET estado_facturacion = 'Facturada' WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdateVenta)) {
                stmt.setInt(1, ventaId);
                stmt.executeUpdate();
            }

            conn.commit(); // Si todo salió bien, confirmamos los cambios
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Factura " + codigoFactura + " generada exitosamente.\"}");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al generar la factura: " + e.getMessage() + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}
