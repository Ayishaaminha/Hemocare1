package com.hemocare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class HemoCareApplication {
  public static void main(String[] args) { SpringApplication.run(HemoCareApplication.class, args); }

  @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

  @Bean CommandLineRunner bootstrapAdmin(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
    return args -> {
      String email = System.getenv("ADMIN_EMAIL");
      String password = System.getenv("ADMIN_PASSWORD");
      if (email != null && !email.isBlank() && password != null && password.length() >= 12) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email.trim().toLowerCase());
        if (exists != null && exists == 0) {
          jdbc.update("INSERT INTO users(name,email,phone,password_hash,role) VALUES(?,?,?,?, 'ADMIN')",
              "HemoCare Administrator", email.trim().toLowerCase(), "Not provided", encoder.encode(password));
        } else {
          jdbc.update("UPDATE users SET role='ADMIN', password_hash=? WHERE email=?", encoder.encode(password), email.trim().toLowerCase());
        }
      }
    };
  }
}
