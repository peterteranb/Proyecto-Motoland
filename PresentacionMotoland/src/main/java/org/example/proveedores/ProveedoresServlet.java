package org.example.proveedores;

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
import java.util.List;
import java.util.Map;

// Modelo para recibir los productos del catálogo desde el JSON
class CatalogoItem {
    String nombreProducto;
    String descripcion;
    double costo;
    String ubicacion; // agregado
}

@WebServlet("/proveedores")
public class ProveedoresServlet extends HttpServlet {

    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        // Definimos tipo de respuesta
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // Leemos el JSON complejo que nos enviará el JavaScript
        Map<String, Object> payload = gson.fromJson(request.getReader(), Map.class);

        String nombreProveedor = (String) payload.get("nombre");
        String contactoProveedor = (String) payload.get("contacto");

        Type catalogoListType = new TypeToken<List<CatalogoItem>>() {}.getType();
        List<CatalogoItem> catalogo = gson.fromJson(gson.toJson(payload.get("catalogo")), catalogoListType);

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // 1. Insertamos el proveedor en la tabla 'proveedores'
            String sqlProveedor = "INSERT INTO proveedores (nombre, contacto) VALUES (?, ?)";
            long proveedorId;

            try (PreparedStatement stmtProveedor = conn.prepareStatement(sqlProveedor, Statement.RETURN_GENERATED_KEYS)) {
                stmtProveedor.setString(1, nombreProveedor);
                stmtProveedor.setString(2, contactoProveedor);
                stmtProveedor.executeUpdate();

                try (ResultSet generatedKeys = stmtProveedor.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        proveedorId = generatedKeys.getLong(1);
                    } else {
                        throw new SQLException("Fallo al crear el proveedor, no se obtuvo ID.");
                    }
                }
            }

            // 2. Insertamos cada producto de su catálogo en la tabla 'catalogo_proveedor'
            // Ahora insertamos también la columna `ubicacion` (si viene)
            String sqlCatalogo = "INSERT INTO catalogo_proveedor (proveedor_id, nombre_producto, descripcion, costo, ubicacion) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmtCatalogo = conn.prepareStatement(sqlCatalogo)) {
                for (CatalogoItem item : catalogo) {
                    stmtCatalogo.setLong(1, proveedorId);
                    stmtCatalogo.setString(2, item.nombreProducto);
                    stmtCatalogo.setString(3, item.descripcion);
                    stmtCatalogo.setDouble(4, item.costo);
                    if (item.ubicacion == null || item.ubicacion.trim().isEmpty()) {
                        stmtCatalogo.setNull(5, Types.VARCHAR);
                    } else {
                        stmtCatalogo.setString(5, item.ubicacion.trim());
                    }
                    stmtCatalogo.addBatch();
                }
                stmtCatalogo.executeBatch();
            }

            conn.commit();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Proveedor y su catálogo registrados exitosamente.\"}");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos. No se guardaron los datos.\"}");
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
