package org.example.clientes;

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

// Modelo para un Cliente (incluye id)
class Cliente {
    private int id;
    private String nombre;
    private String contacto;
    private String direccion;

    public Cliente(int id, String nombre, String contacto, String direccion) {
        this.id = id;
        this.nombre = nombre;
        this.contacto = contacto;
        this.direccion = direccion;
    }

    // Getters (Gson los usa automáticamente al serializar)
    public int getId() { return id; }
    public String getNombre() { return nombre; }
    public String getContacto() { return contacto; }
    public String getDireccion() { return direccion; }
}

@WebServlet("/clientes")
public class ClientesServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<Cliente> clientes = new ArrayList<>();
        String sql = "SELECT id, nombre, contacto, direccion FROM clientes";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                clientes.add(new Cliente(
                        rs.getInt("id"),
                        rs.getString("nombre"),
                        rs.getString("contacto"),
                        rs.getString("direccion")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"Error al obtener datos de clientes.\"}");
            return;
        }

        response.getWriter().write(gson.toJson(clientes));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");

        String nombre = request.getParameter("nombre");
        String contacto = request.getParameter("contacto");
        String direccion = request.getParameter("direccion");

        String sql = "INSERT INTO clientes (nombre, contacto, direccion) VALUES (?, ?, ?)";

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, nombre);
            stmt.setString(2, contacto);
            stmt.setString(3, direccion);

            int filasAfectadas = stmt.executeUpdate();

            if (filasAfectadas > 0) {
                response.getWriter().write("{\"status\":\"success\", \"message\":\"Cliente registrado exitosamente\"}");
            } else {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write("{\"status\":\"error\", \"message\":\"No se pudo registrar el cliente\"}");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos\"}");
        }
    }
}
