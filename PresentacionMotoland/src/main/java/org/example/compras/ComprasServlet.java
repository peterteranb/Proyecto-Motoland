package org.example.compras;

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

/**
 * ComprasServlet mejorado:
 * - GET: list providers / search q / get catalog by proveedorId (incluye ubicacion)
 * - POST: registra compra, actualiza/crea productos, actualiza stock y ubicacion, inserta detalle_compras
 */

class ProveedorSimple {
    private int id;
    private String nombre;
    public ProveedorSimple(int id, String nombre) { this.id = id; this.nombre = nombre; }
}

class CatalogoProducto {
    private int id;
    // Nombres de campo pensados para serializar igual que en DB / frontend
    private String nombre_producto;
    private double costo;
    private String ubicacion;

    public CatalogoProducto(int id, String nombre_producto, double costo, String ubicacion) {
        this.id = id;
        this.nombre_producto = nombre_producto;
        this.costo = costo;
        this.ubicacion = ubicacion;
    }
}

class CompraItem {
    // campos esperados en el JSON: nombreProducto, cantidad, costoUnitario, ubicacion, descripcion (opcional)
    String nombreProducto;
    int cantidad;
    double costoUnitario;
    String descripcion;
    String ubicacion;
}

@WebServlet("/compras")
public class ComprasServlet extends HttpServlet {
    private final Gson gson = new Gson();

    /**
     * GET:
     * - /compras                 -> lista proveedores (id,nombre)
     * - /compras?q=texto         -> busca proveedores por nombre (LIKE)
     * - /compras?proveedorId=NN  -> devuelve catálogo del proveedor (id, nombre_producto, costo, ubicacion)
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");

        String proveedorIdParam = request.getParameter("proveedorId");
        String q = request.getParameter("q");

        if (proveedorIdParam != null && !proveedorIdParam.trim().isEmpty()) {
            // Devolver catálogo del proveedor (incluye ubicacion)
            int proveedorId;
            try {
                proveedorId = Integer.parseInt(proveedorIdParam);
            } catch (NumberFormatException ex) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write("{\"status\":\"error\",\"message\":\"proveedorId inválido\"}");
                return;
            }
            List<CatalogoProducto> catalogo = new ArrayList<>();
            String sql = "SELECT id, nombre_producto, costo, ubicacion FROM catalogo_proveedor WHERE proveedor_id = ?";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, proveedorId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        catalogo.add(new CatalogoProducto(
                                rs.getInt("id"),
                                rs.getString("nombre_producto"),
                                rs.getDouble("costo"),
                                rs.getString("ubicacion")
                        ));
                    }
                }
                response.getWriter().write(gson.toJson(catalogo));
            } catch (SQLException e) {
                e.printStackTrace();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{\"status\":\"error\",\"message\":\"Error al obtener catálogo\"}");
            }
            return;
        }

        // Si viene q -> búsqueda por nombre de proveedor (autocomplete)
        if (q != null && !q.trim().isEmpty()) {
            List<ProveedorSimple> resultados = new ArrayList<>();
            String sql = "SELECT id, nombre FROM proveedores WHERE nombre LIKE ? ORDER BY nombre LIMIT 30";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, "%" + q.trim() + "%");
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        resultados.add(new ProveedorSimple(rs.getInt("id"), rs.getString("nombre")));
                    }
                }
                response.getWriter().write(gson.toJson(resultados));
            } catch (SQLException e) {
                e.printStackTrace();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{\"status\":\"error\",\"message\":\"Error en búsqueda de proveedores\"}");
            }
            return;
        }

        // Por defecto devolver la lista completa de proveedores (id,nombre)
        List<ProveedorSimple> proveedores = new ArrayList<>();
        String sql = "SELECT id, nombre FROM proveedores ORDER BY nombre";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                proveedores.add(new ProveedorSimple(rs.getInt("id"), rs.getString("nombre")));
            }
            response.getWriter().write(gson.toJson(proveedores));
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"Error al obtener proveedores\"}");
        }
    }

    /**
     * POST: registra una compra.
     * JSON esperado:
     * {
     *   "proveedorId": 3,
     *   "codigo_compra": "OPCIONAL",
     *   "fecha_compra": "2025-09-10T12:00",
     *   "estado": "Completada",
     *   "items": [ { "nombreProducto":"...", "cantidad":1, "costoUnitario":100.0, "ubicacion":"P1/R2/A" }, ... ]
     * }
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");

        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> payload = gson.fromJson(request.getReader(), mapType);

        if (payload == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"Payload inválido\"}");
            return;
        }

        // obtener proveedorId: puede venir como número o (por seguridad) intentar resolver por nombre
        Integer proveedorId = null;
        if (payload.containsKey("proveedorId") && payload.get("proveedorId") != null) {
            try {
                // Gson puede parsearlo como Double -> handle
                Object pid = payload.get("proveedorId");
                if (pid instanceof Number) proveedorId = ((Number) pid).intValue();
                else proveedorId = Integer.parseInt(pid.toString());
            } catch (Exception ignore) { proveedorId = null; }
        } else if (payload.containsKey("proveedor") && payload.get("proveedor") != null) {
            // intentar resolver proveedor por nombre
            String provName = String.valueOf(payload.get("proveedor")).trim();
            if (!provName.isEmpty()) {
                try (Connection conn = DatabaseConnection.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT id FROM proveedores WHERE nombre = ? LIMIT 1")) {
                    stmt.setString(1, provName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) proveedorId = rs.getInt("id");
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        if (proveedorId == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"proveedorId es requerido o proveedor no encontrado\"}");
            return;
        }

        Type itemListType = new TypeToken<List<CompraItem>>() {}.getType();
        List<CompraItem> items = new ArrayList<>();
        if (payload.containsKey("items") && payload.get("items") != null) {
            items = gson.fromJson(gson.toJson(payload.get("items")), itemListType);
        }

        if (items.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"La compra debe contener al menos un ítem\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // 1. Calcular total
            double totalCompra = items.stream().mapToDouble(i -> i.cantidad * i.costoUnitario).sum();

            // 2. Insertar compra (proveedor_id, total)
            long compraId;
            String sqlCompra = "INSERT INTO compras (proveedor_id, total) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sqlCompra, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, proveedorId);
                ps.setDouble(2, totalCompra);
                ps.executeUpdate();
                try (ResultSet g = ps.getGeneratedKeys()) {
                    if (g.next()) compraId = g.getLong(1);
                    else throw new SQLException("No se pudo obtener ID de compra");
                }
            }

            // 3. Generar código y actualizar
            String codigoCompra = "COMPRA-" + compraId;
            String sqlUpdCode = "UPDATE compras SET codigo_compra = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlUpdCode)) {
                ps.setString(1, codigoCompra);
                ps.setLong(2, compraId);
                ps.executeUpdate();
            }

            // 4. Preparar inserción de detalle
            String sqlDetalle = "INSERT INTO detalle_compras (compra_id, producto_id, cantidad, costo_unitario, subtotal) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement psDetalle = conn.prepareStatement(sqlDetalle)) {

                // Reutilizaremos queries para buscar producto por nombre y para crear producto nuevo
                String sqlFindProductByName = "SELECT id FROM productos WHERE nombre = ?";
                String sqlUpdateStockAndUbic = "UPDATE productos SET stock = stock + ?, ubicacion = ? WHERE id = ?";
                String sqlUpdateStock = "UPDATE productos SET stock = stock + ? WHERE id = ?";
                String sqlInsertProduct = "INSERT INTO productos (nombre, categoria, medida, precio_venta, ubicacion) VALUES (?, ?, ?, ?, ?)";
                String sqlUpdateProductCodigo = "UPDATE productos SET codigo = ? WHERE id = ?";

                for (CompraItem item : items) {
                    long productoId = -1;

                    // intentar encontrar producto por nombre exacto
                    try (PreparedStatement psFind = conn.prepareStatement(sqlFindProductByName)) {
                        psFind.setString(1, item.nombreProducto);
                        try (ResultSet rs = psFind.executeQuery()) {
                            if (rs.next()) {
                                productoId = rs.getLong("id");
                                // actualizar stock y ubicacion si viene
                                if (item.ubicacion != null && !item.ubicacion.trim().isEmpty()) {
                                    try (PreparedStatement psUp = conn.prepareStatement(sqlUpdateStockAndUbic)) {
                                        psUp.setInt(1, item.cantidad);
                                        psUp.setString(2, item.ubicacion.trim());
                                        psUp.setLong(3, productoId);
                                        psUp.executeUpdate();
                                    }
                                } else {
                                    try (PreparedStatement psUp = conn.prepareStatement(sqlUpdateStock)) {
                                        psUp.setInt(1, item.cantidad);
                                        psUp.setLong(2, productoId);
                                        psUp.executeUpdate();
                                    }
                                }
                            }
                        }
                    }

                    // si no existe el producto -> crearlo (y asignar stock)
                    if (productoId == -1) {
                        try (PreparedStatement psIns = conn.prepareStatement(sqlInsertProduct, Statement.RETURN_GENERATED_KEYS)) {
                            psIns.setString(1, item.nombreProducto);
                            psIns.setString(2, "General");
                            psIns.setString(3, "Unidad");
                            // establecemos precio_venta base como markup del costo (ej 1.5)
                            psIns.setDouble(4, item.costoUnitario * 1.5);
                            if (item.ubicacion == null || item.ubicacion.trim().isEmpty()) {
                                psIns.setNull(5, Types.VARCHAR);
                            } else {
                                psIns.setString(5, item.ubicacion.trim());
                            }
                            psIns.executeUpdate();
                            try (ResultSet gk = psIns.getGeneratedKeys()) {
                                if (gk.next()) {
                                    productoId = gk.getLong(1);
                                    // generar codigo producto
                                    String codigoProducto = "PROD-" + productoId;
                                    try (PreparedStatement psUpd = conn.prepareStatement(sqlUpdateProductCodigo)) {
                                        psUpd.setString(1, codigoProducto);
                                        psUpd.setLong(2, productoId);
                                        psUpd.executeUpdate();
                                    }
                                    // inicializar stock
                                    try (PreparedStatement psStock = conn.prepareStatement("UPDATE productos SET stock = stock + ? WHERE id = ?")) {
                                        psStock.setInt(1, item.cantidad);
                                        psStock.setLong(2, productoId);
                                        psStock.executeUpdate();
                                    }
                                } else {
                                    throw new SQLException("No se pudo crear producto nuevo.");
                                }
                            }
                        }
                    }

                    // Finalmente insertar línea detalle (ya tenemos productoId)
                    psDetalle.setLong(1, compraId);
                    psDetalle.setLong(2, productoId);
                    psDetalle.setInt(3, item.cantidad);
                    psDetalle.setDouble(4, item.costoUnitario);
                    psDetalle.setDouble(5, item.cantidad * item.costoUnitario);
                    psDetalle.addBatch();
                }

                // Ejecutar batch detalle
                psDetalle.executeBatch();
            }

            // commit
            conn.commit();
            response.getWriter().write("{\"status\":\"success\",\"message\":\"Compra " + codigoCompra + " registrada y stock actualizado.\"}");
        } catch (Exception e) {
            e.printStackTrace();
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"Error en la base de datos: " + e.getMessage().replace("\"","'") + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}
