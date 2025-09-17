package org.example.devoluciones;

import com.google.gson.Gson;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;

// Modelo para recibir los datos del HTML (nombres de campos JSON)
class DevolucionRequest {
    String tipo;
    String codigo;
    String motivo;
    // Opcional: producto y ubicacion (si el usuario lo indica)
    String producto;
    String ubicacion_zona;
    String ubicacion_rack;
    String ubicacion_altura;
}

@WebServlet("/devoluciones")
public class DevolucionesServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        DevolucionRequest devReq = gson.fromJson(request.getReader(), DevolucionRequest.class);
        Connection conn = null;
        String nuevoCodigoDevolucion = "";

        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); // Iniciamos transacción

            // Respuestas en JSON y UTF-8
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            if (devReq == null) throw new SQLException("Payload inválido.");

            if ("Venta".equalsIgnoreCase(devReq.tipo)) {
                // --- Anular venta: devolver stock ---
                String sqlFind = "SELECT id, estado FROM ventas WHERE codigo_venta = ?";
                int ventaId = -1;
                try (PreparedStatement stmt = conn.prepareStatement(sqlFind)) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new SQLException("No se encontró la venta con el código: " + devReq.codigo);
                        if ("Anulada".equalsIgnoreCase(rs.getString("estado"))) throw new SQLException("Esta venta ya ha sido anulada.");
                        ventaId = rs.getInt("id");
                    }
                }

                String sqlGetItems = "SELECT producto_id, cantidad FROM detalle_ventas WHERE venta_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlGetItems)) {
                    stmt.setInt(1, ventaId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String sqlStock = "UPDATE productos SET stock = stock + ? WHERE id = ?";
                            try (PreparedStatement stmtStock = conn.prepareStatement(sqlStock)) {
                                stmtStock.setInt(1, rs.getInt("cantidad"));
                                stmtStock.setInt(2, rs.getInt("producto_id"));
                                stmtStock.executeUpdate();
                            }
                        }
                    }
                }

                String sqlUpdate = "UPDATE ventas SET estado = 'Anulada' WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                    stmt.setInt(1, ventaId);
                    stmt.executeUpdate();
                }

            } else if ("Compra".equalsIgnoreCase(devReq.tipo)) {
                // --- Anular compra: quitar stock (si hay suficiente) ---
                String sqlFind = "SELECT id, estado FROM compras WHERE codigo_compra = ?";
                int compraId = -1;
                try (PreparedStatement stmt = conn.prepareStatement(sqlFind)) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new SQLException("No se encontró la compra con el código: " + devReq.codigo);
                        if ("Anulada".equalsIgnoreCase(rs.getString("estado"))) throw new SQLException("Esta compra ya ha sido anulada.");
                        compraId = rs.getInt("id");
                    }
                }

                String sqlGetItems = "SELECT producto_id, cantidad FROM detalle_compras WHERE compra_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlGetItems)) {
                    stmt.setInt(1, compraId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            int productoId = rs.getInt("producto_id");
                            int cantidad = rs.getInt("cantidad");

                            String sqlCheckStock = "SELECT stock FROM productos WHERE id = ?";
                            try (PreparedStatement stmtCheck = conn.prepareStatement(sqlCheckStock)) {
                                stmtCheck.setInt(1, productoId);
                                try (ResultSet rsStock = stmtCheck.executeQuery()) {
                                    if (rsStock.next() && rsStock.getInt("stock") < cantidad) {
                                        throw new SQLException("Stock insuficiente para anular la compra. Producto ID " + productoId + " stock actual: " + rsStock.getInt("stock"));
                                    }
                                }
                            }
                            String sqlStock = "UPDATE productos SET stock = stock - ? WHERE id = ?";
                            try (PreparedStatement stmtStock = conn.prepareStatement(sqlStock)) {
                                stmtStock.setInt(1, cantidad);
                                stmtStock.setInt(2, productoId);
                                stmtStock.executeUpdate();
                            }
                        }
                    }
                }

                String sqlUpdate = "UPDATE compras SET estado = 'Anulada' WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                    stmt.setInt(1, compraId);
                    stmt.executeUpdate();
                }

            } else {
                throw new SQLException("Tipo de transacción no válido. Use 'Venta' o 'Compra'.");
            }

            // Si el usuario incluyó 'producto' y las tres partes de ubicacion están completas,
            // actualizamos la columna 'productos.ubicacion' para ese producto (por codigo o nombre).
            if (devReq.producto != null && !devReq.producto.trim().isEmpty()) {
                boolean anyUbicProvided = (devReq.ubicacion_zona != null && !devReq.ubicacion_zona.trim().isEmpty())
                        || (devReq.ubicacion_rack != null && !devReq.ubicacion_rack.trim().isEmpty())
                        || (devReq.ubicacion_altura != null && !devReq.ubicacion_altura.trim().isEmpty());

                if (anyUbicProvided) {
                    // require all three or none
                    if (devReq.ubicacion_zona == null || devReq.ubicacion_zona.trim().isEmpty()
                            || devReq.ubicacion_rack == null || devReq.ubicacion_rack.trim().isEmpty()
                            || devReq.ubicacion_altura == null || devReq.ubicacion_altura.trim().isEmpty()) {
                        throw new SQLException("Ubicación incompleta: debe seleccionar Zona, Rack y Altura o dejar los tres vacíos.");
                    }

                    // buscar producto por codigo o nombre
                    String sqlFindProd = "SELECT id FROM productos WHERE codigo = ? OR nombre = ? LIMIT 1";
                    Integer productoId = null;
                    try (PreparedStatement ps = conn.prepareStatement(sqlFindProd)) {
                        ps.setString(1, devReq.producto);
                        ps.setString(2, devReq.producto);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) productoId = rs.getInt("id");
                        }
                    }

                    if (productoId == null) {
                        // no frenar todo: intentar crear el producto mínimo (si prefieres no crear, comentar este bloque)
                        String sqlCreate = "INSERT INTO productos (nombre, categoria, medida, precio_venta, stock) VALUES (?, 'General', 'Unidad', 0.0, 0)";
                        try (PreparedStatement ps = conn.prepareStatement(sqlCreate, Statement.RETURN_GENERATED_KEYS)) {
                            ps.setString(1, devReq.producto);
                            ps.executeUpdate();
                            try (ResultSet gk = ps.getGeneratedKeys()) {
                                if (gk.next()) productoId = gk.getInt(1);
                            }
                        }
                        // asignar código producto si deseas
                        if (productoId != null) {
                            String codigoProducto = "PROD-" + productoId;
                            try (PreparedStatement ps = conn.prepareStatement("UPDATE productos SET codigo = ? WHERE id = ?")) {
                                ps.setString(1, codigoProducto);
                                ps.setInt(2, productoId);
                                ps.executeUpdate();
                            }
                        }
                    }

                    if (productoId != null) {
                        String ubic = String.format("P%s/R%s/%s",
                                devReq.ubicacion_zona.trim(),
                                devReq.ubicacion_rack.trim(),
                                devReq.ubicacion_altura.trim().toUpperCase());
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE productos SET ubicacion = ? WHERE id = ?")) {
                            ps.setString(1, ubic);
                            ps.setInt(2, productoId);
                            ps.executeUpdate();
                        }
                    }
                }
            }

            // Registrar la devolución en la tabla 'devoluciones'
            String sqlLog = "INSERT INTO devoluciones (tipo_transaccion_original, id_transaccion_original, codigo_transaccion_original, motivo) VALUES (?, ?, ?, ?)";
            long devId;
            int transId = -1;

            if ("Venta".equalsIgnoreCase(devReq.tipo)) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM ventas WHERE codigo_venta = ?")) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) transId = rs.getInt("id");
                    }
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM compras WHERE codigo_compra = ?")) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) transId = rs.getInt("id");
                    }
                }
            }
            if (transId == -1) throw new SQLException("No se pudo encontrar el ID de la transacción original.");

            try (PreparedStatement stmt = conn.prepareStatement(sqlLog, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, devReq.tipo);
                stmt.setInt(2, transId);
                stmt.setString(3, devReq.codigo);
                stmt.setString(4, devReq.motivo);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) devId = rs.getLong(1);
                    else throw new SQLException("No se pudo registrar la devolución.");
                }
            }

            nuevoCodigoDevolucion = "DEV-" + devId;
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE devoluciones SET codigo_devolucion = ? WHERE id = ?")) {
                stmt.setString(1, nuevoCodigoDevolucion);
                stmt.setLong(2, devId);
                stmt.executeUpdate();
            }

            conn.commit();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Devolución " + nuevoCodigoDevolucion + " procesada exitosamente.\"}");
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            // Aseguramos que el mensaje JSON esté escapado simple
            String safe = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Error desconocido";
            response.getWriter().write("{\"status\":\"error\", \"message\":\"" + safe + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}
