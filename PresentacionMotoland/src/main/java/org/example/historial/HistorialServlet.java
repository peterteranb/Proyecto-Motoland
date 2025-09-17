package org.example.historial;

import com.google.gson.Gson;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// Modelo actualizado para una transacción del historial
class Transaccion {
    private String tipo;
    private String codigo;
    private String fecha;
    private double total;
    private String detalle;
    private String estado;
    private String ubicacion; // nuevo campo para colocar la(s) ubicacion(es)

    public Transaccion(String tipo, String codigo, String fecha, double total, String detalle, String estado, String ubicacion) {
        this.tipo = tipo;
        this.codigo = codigo;
        this.fecha = fecha;
        this.total = total;
        this.detalle = detalle;
        this.estado = estado;
        this.ubicacion = ubicacion;
    }

    public String getFecha() {
        return fecha;
    }
}

@WebServlet("/historial")
public class HistorialServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<Transaccion> historial = new ArrayList<>();

        try (Connection conn = DatabaseConnection.getConnection()) {

            // 1) VENTAS: obtengo info, items agregados y ubicaciones (si existen)
            String sqlVentas =
                    "SELECT v.id, v.codigo_venta, v.fecha_venta, v.total, v.estado, c.nombre as cliente_nombre, " +
                            "       GROUP_CONCAT(CONCAT(dv.cantidad, 'x ', p.nombre) SEPARATOR ' | ') AS productos_info, " +
                            "       GROUP_CONCAT(DISTINCT COALESCE(p.ubicacion, '-') SEPARATOR ', ') AS ubicaciones " +
                            "FROM ventas v " +
                            "LEFT JOIN clientes c ON v.cliente_id = c.id " +
                            "LEFT JOIN detalle_ventas dv ON dv.venta_id = v.id " +
                            "LEFT JOIN productos p ON dv.producto_id = p.id " +
                            "GROUP BY v.id";

            try (PreparedStatement stmt = conn.prepareStatement(sqlVentas);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String cliente = rs.getString("cliente_nombre");
                    String productosInfo = rs.getString("productos_info");
                    String ubicaciones = rs.getString("ubicaciones");

                    String detalle = "Cliente: " + ((cliente != null) ? cliente : "Cliente Anónimo");
                    if (productosInfo != null && !productosInfo.trim().isEmpty()) {
                        detalle += " | Items: " + productosInfo;
                    }

                    historial.add(new Transaccion(
                            "Venta",
                            rs.getString("codigo_venta"),
                            rs.getTimestamp("fecha_venta").toString(),
                            rs.getDouble("total"),
                            detalle,
                            rs.getString("estado"),
                            (ubicaciones != null && !ubicaciones.trim().isEmpty()) ? ubicaciones : "-" // ubicacion separada
                    ));
                }
            }

            // 2) COMPRAS: idem, obteniendo proveedor, items y ubicaciones
            String sqlCompras =
                    "SELECT c.id, c.codigo_compra, c.fecha_compra, c.total, c.estado, p.nombre as proveedor_nombre, " +
                            "       GROUP_CONCAT(CONCAT(dc.cantidad, 'x ', prod.nombre) SEPARATOR ' | ') AS productos_info, " +
                            "       GROUP_CONCAT(DISTINCT COALESCE(prod.ubicacion, '-') SEPARATOR ', ') AS ubicaciones " +
                            "FROM compras c " +
                            "LEFT JOIN proveedores p ON c.proveedor_id = p.id " +
                            "LEFT JOIN detalle_compras dc ON dc.compra_id = c.id " +
                            "LEFT JOIN productos prod ON dc.producto_id = prod.id " +
                            "GROUP BY c.id";

            try (PreparedStatement stmt = conn.prepareStatement(sqlCompras);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String proveedor = rs.getString("proveedor_nombre");
                    String productosInfo = rs.getString("productos_info");
                    String ubicaciones = rs.getString("ubicaciones");

                    String detalle = "Proveedor: " + ((proveedor != null) ? proveedor : "Proveedor Desconocido");
                    if (productosInfo != null && !productosInfo.trim().isEmpty()) {
                        detalle += " | Items: " + productosInfo;
                    }

                    historial.add(new Transaccion(
                            "Compra",
                            rs.getString("codigo_compra"),
                            rs.getTimestamp("fecha_compra").toString(),
                            rs.getDouble("total"),
                            detalle,
                            rs.getString("estado"),
                            (ubicaciones != null && !ubicaciones.trim().isEmpty()) ? ubicaciones : "-"
                    ));
                }
            }

            // 3) DEVOLUCIONES: por ahora no suelen tener ubicaciones asociadas directamente
            String sqlDevoluciones = "SELECT id, codigo_devolucion, fecha_devolucion, codigo_transaccion_original, tipo_transaccion_original, motivo FROM devoluciones";
            try (PreparedStatement stmt = conn.prepareStatement(sqlDevoluciones);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String detalle = "Anulación de: " + rs.getString("codigo_transaccion_original");
                    String motivo = rs.getString("motivo");
                    if (motivo != null && !motivo.trim().isEmpty()) detalle += " | Motivo: " + motivo;

                    historial.add(new Transaccion(
                            "Devolución",
                            rs.getString("codigo_devolucion"),
                            rs.getTimestamp("fecha_devolucion").toString(),
                            0.00,
                            detalle,
                            "Procesada",
                            "-" // sin ubicacion asociada por defecto
                    ));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al cargar el historial.\"}");
            return;
        }

        // Ordenamos por fecha (desc)
        historial.sort((t1, t2) -> t2.getFecha().compareTo(t1.getFecha()));

        response.getWriter().write(gson.toJson(historial));
    }
}
