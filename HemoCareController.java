package com.hemocare;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class HemoCareController {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords;
  private static final Set<String> GROUPS = Set.of("O+","A+","B+","AB+","O-","A-","B-","AB-");

  public HemoCareController(JdbcTemplate db, BCryptPasswordEncoder passwords) { this.db = db; this.passwords = passwords; }

  private Map<String,Object> result(String message) { return new LinkedHashMap<>(Map.of("message", message)); }
  private ResponseEntity<Map<String,Object>> error(HttpStatus status, String message) { return ResponseEntity.status(status).body(result(message)); }
  private String str(Map<String,Object> body, String key) { Object v = body.get(key); return v == null ? "" : String.valueOf(v).trim(); }
  private long number(Object value, long fallback) { try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return fallback; } }
  private Map<String,Object> current(HttpSession session) { Object u = session.getAttribute("user"); return u instanceof Map<?,?> ? (Map<String,Object>)u : null; }
  private ResponseEntity<Map<String,Object>> requireUser(HttpSession session) { return error(HttpStatus.UNAUTHORIZED,"Please log in to continue."); }
  private boolean admin(HttpSession session) { Map<String,Object> u=current(session); return u != null && "ADMIN".equals(u.get("role")); }
  private boolean loggedIn(HttpSession session) { return current(session) != null; }
  private boolean validGroup(String g) { return GROUPS.contains(g); }

  @GetMapping("/health") public Map<String,Object> health() { return Map.of("status","ok","service","HemoCare"); }

  @GetMapping("/stock") public List<Map<String,Object>> stock() {
    return db.query("SELECT blood_group, units FROM blood_inventory ORDER BY FIELD(blood_group,'O+','A+','B+','AB+','O-','A-','B-','AB-')",
      (rs,n)->Map.of("bloodGroup",rs.getString("blood_group"),"units",rs.getInt("units")));
  }

  @PostMapping("/register") public ResponseEntity<Map<String,Object>> register(@RequestBody Map<String,Object> b, HttpSession session) {
    String name=str(b,"name"), email=str(b,"email").toLowerCase(), phone=str(b,"phone"), password=str(b,"password");
    if(name.length()<2 || name.length()>100) return error(HttpStatus.BAD_REQUEST,"Enter your full name (2–100 characters).");
    if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return error(HttpStatus.BAD_REQUEST,"Enter a valid email address.");
    if(phone.length()<7 || phone.length()>30) return error(HttpStatus.BAD_REQUEST,"Enter a valid phone number.");
    if(password.length()<6 || password.length()>72) return error(HttpStatus.BAD_REQUEST,"Password must be between 6 and 72 characters.");
    try {
      db.update("INSERT INTO users(name,email,phone,password_hash,role) VALUES(?,?,?,?, 'USER')",name,email,phone,passwords.encode(password));
      Map<String,Object> user=db.queryForMap("SELECT id,name,email,phone,role FROM users WHERE email=?",email);
      session.setAttribute("user",user);
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message","Welcome to HemoCare! Your account is ready.","user",user));
    } catch(DuplicateKeyException e) { return error(HttpStatus.CONFLICT,"An account with this email already exists. Please log in instead."); }
  }

  @PostMapping("/login") public ResponseEntity<Map<String,Object>> login(@RequestBody Map<String,Object> b, HttpServletRequest request) {
    String email=str(b,"email").toLowerCase(), password=str(b,"password");
    List<Map<String,Object>> rows=db.query("SELECT id,name,email,phone,password_hash,role FROM users WHERE email=?",(rs,n)->{
      Map<String,Object> m=new LinkedHashMap<>(); m.put("id",rs.getLong("id"));m.put("name",rs.getString("name"));m.put("email",rs.getString("email"));m.put("phone",rs.getString("phone"));m.put("password_hash",rs.getString("password_hash"));m.put("role",rs.getString("role"));return m;
    },email);
    if(rows.isEmpty() || !passwords.matches(password,(String)rows.get(0).get("password_hash"))) return error(HttpStatus.UNAUTHORIZED,"Email or password is incorrect.");
    Map<String,Object> user=rows.get(0); user.remove("password_hash");
    HttpSession old=request.getSession(false); if(old!=null) old.invalidate();
    request.getSession(true).setAttribute("user",user);
    return ResponseEntity.ok(Map.of("message","Welcome back, "+user.get("name")+"!","user",user));
  }

  @PostMapping("/logout") public Map<String,Object> logout(HttpSession session) { session.invalidate(); return result("You have been logged out safely."); }

  @GetMapping("/me") public ResponseEntity<Map<String,Object>> me(HttpSession session) {
    Map<String,Object> u=current(session); if(u==null) return error(HttpStatus.UNAUTHORIZED,"Not logged in.");
    return ResponseEntity.ok(Map.of("user",u));
  }

  @PostMapping("/donations") public ResponseEntity<Map<String,Object>> donate(@RequestBody Map<String,Object> b,HttpSession session) {
    Map<String,Object> u=current(session); if(u==null)return error(HttpStatus.UNAUTHORIZED,"Please log in to submit a donation.");
    String group=str(b,"bloodGroup"), date=str(b,"donationDate"); long units=number(b.get("units"),0);
    if(!validGroup(group))return error(HttpStatus.BAD_REQUEST,"Choose a valid blood group.");
    if(units<1||units>5)return error(HttpStatus.BAD_REQUEST,"Donation units must be between 1 and 5.");
    LocalDate d; try {d=LocalDate.parse(date);}catch(Exception e){return error(HttpStatus.BAD_REQUEST,"Choose a valid donation date.");}
    if(d.isBefore(LocalDate.now())||d.isAfter(LocalDate.now().plusMonths(3)))return error(HttpStatus.BAD_REQUEST,"Choose a date from today to the next three months.");
    db.update("INSERT INTO donations(user_id,blood_group,units,donation_date,status) VALUES(?,?,?,?, 'PENDING')",u.get("id"),group,units,Date.valueOf(d));
    return ResponseEntity.status(HttpStatus.CREATED).body(result("Donation submitted. Inventory will update after admin approval.") );
  }

  @PostMapping("/requests") public ResponseEntity<Map<String,Object>> requestBlood(@RequestBody Map<String,Object> b,HttpSession session) {
    Map<String,Object> u=current(session); if(u==null)return error(HttpStatus.UNAUTHORIZED,"Please log in to request blood.");
    String group=str(b,"bloodGroup"), patient=str(b,"patientName"), hospital=str(b,"hospital"), reason=str(b,"reason"); long units=number(b.get("units"),0);
    if(!validGroup(group))return error(HttpStatus.BAD_REQUEST,"Choose a valid blood group.");
    if(units<1||units>10)return error(HttpStatus.BAD_REQUEST,"Request units must be between 1 and 10.");
    if(patient.length()<2||patient.length()>100||hospital.length()<2||hospital.length()>160||reason.length()<3||reason.length()>500)return error(HttpStatus.BAD_REQUEST,"Please complete patient name, hospital, and reason (maximum 500 characters).");
    db.update("INSERT INTO blood_requests(user_id,blood_group,units,patient_name,hospital,reason,status) VALUES(?,?,?,?,?,?, 'PENDING')",u.get("id"),group,units,patient,hospital,reason);
    return ResponseEntity.status(HttpStatus.CREATED).body(result("Blood request submitted for review. You can track its status in your dashboard."));
  }

  @GetMapping("/my-requests") public ResponseEntity<?> myRequests(HttpSession s) { Map<String,Object> u=current(s);if(u==null)return error(HttpStatus.UNAUTHORIZED,"Please log in."); return ResponseEntity.ok(db.query("SELECT id,blood_group AS bloodGroup,units,patient_name AS patientName,hospital,reason,status,created_at AS createdAt FROM blood_requests WHERE user_id=? ORDER BY created_at DESC",mapRow(),u.get("id"))); }
  @GetMapping("/my-donations") public ResponseEntity<?> myDonations(HttpSession s) { Map<String,Object> u=current(s);if(u==null)return error(HttpStatus.UNAUTHORIZED,"Please log in."); return ResponseEntity.ok(db.query("SELECT id,blood_group AS bloodGroup,units,donation_date AS donationDate,status,created_at AS createdAt FROM donations WHERE user_id=? ORDER BY created_at DESC",mapRow(),u.get("id"))); }

  @GetMapping("/admin/requests") public ResponseEntity<?> allRequests(HttpSession s) { if(!admin(s))return error(HttpStatus.FORBIDDEN,"Administrator access required."); return ResponseEntity.ok(db.query("SELECT r.id,r.patient_name AS patientName,r.blood_group AS bloodGroup,r.units,r.hospital,r.reason,r.status,r.created_at AS createdAt,u.name AS donorName,u.email AS userEmail FROM blood_requests r JOIN users u ON u.id=r.user_id ORDER BY r.created_at DESC",mapRow())); }
  @GetMapping("/admin/donations") public ResponseEntity<?> allDonations(HttpSession s) { if(!admin(s))return error(HttpStatus.FORBIDDEN,"Administrator access required."); return ResponseEntity.ok(db.query("SELECT d.id,d.blood_group AS bloodGroup,d.units,d.donation_date AS donationDate,d.status,d.created_at AS createdAt,u.name AS donorName,u.email AS userEmail FROM donations d JOIN users u ON u.id=d.user_id ORDER BY d.created_at DESC",mapRow())); }

  @PostMapping("/admin/requests/{id}/approve") @Transactional public ResponseEntity<Map<String,Object>> approveRequest(@PathVariable long id,HttpSession s) {
    if(!admin(s))return error(HttpStatus.FORBIDDEN,"Administrator access required.");
    List<Map<String,Object>> rows=db.query("SELECT blood_group AS bloodGroup,units,status FROM blood_requests WHERE id=? FOR UPDATE",mapRow(),id);
    if(rows.isEmpty())return error(HttpStatus.NOT_FOUND,"Blood request not found."); Map<String,Object> r=rows.get(0);
    if(!"PENDING".equals(r.get("status")))return error(HttpStatus.CONFLICT,"This request has already been reviewed.");
    String group=String.valueOf(r.get("bloodGroup")); int units=((Number)r.get("units")).intValue();
    int changed=db.update("UPDATE blood_inventory SET units=units-? WHERE blood_group=? AND units>=?",units,group,units);
    if(changed==0)return error(HttpStatus.CONFLICT,"Not enough units available for this request. Stock has not changed.");
    db.update("UPDATE blood_requests SET status='APPROVED' WHERE id=? AND status='PENDING'",id);
    return ResponseEntity.ok(result("Request approved and inventory updated."));
  }
  @PostMapping("/admin/requests/{id}/reject") public ResponseEntity<Map<String,Object>> rejectRequest(@PathVariable long id,HttpSession s) {
    if(!admin(s))return error(HttpStatus.FORBIDDEN,"Administrator access required."); int n=db.update("UPDATE blood_requests SET status='REJECTED' WHERE id=? AND status='PENDING'",id);
    if(n==0)return error(HttpStatus.CONFLICT,"Request not found or already reviewed.");return ResponseEntity.ok(result("Request rejected."));
  }
  @PostMapping("/admin/donations/{id}/approve") @Transactional public ResponseEntity<Map<String,Object>> approveDonation(@PathVariable long id,HttpSession s) {
    if(!admin(s))return error(HttpStatus.FORBIDDEN,"Administrator access required.");
    List<Map<String,Object>> rows=db.query("SELECT blood_group AS bloodGroup,units,status FROM donations WHERE id=? FOR UPDATE",mapRow(),id);
    if(rows.isEmpty())return error(HttpStatus.NOT_FOUND,"Donation not found."); Map<String,Object> d=rows.get(0);
    if(!"PENDING".equals(d.get("status")))return error(HttpStatus.CONFLICT,"This donation has already been reviewed.");
    int changed=db.update("UPDATE blood_inventory SET units=units+? WHERE blood_group=?",d.get("units"),d.get("bloodGroup"));
    if(changed==0)return error(HttpStatus.CONFLICT,"Blood group is missing from inventory. No changes were made.");
    db.update("UPDATE donations SET status='APPROVED' WHERE id=? AND status='PENDING'",id);
    return ResponseEntity.ok(result("Donation approved and inventory updated."));
  }
  private RowMapper<Map<String,Object>> mapRow() { return (rs,n)-> { Map<String,Object> m=new LinkedHashMap<>(); var md=rs.getMetaData(); for(int i=1;i<=md.getColumnCount();i++){Object v=rs.getObject(i); if(v instanceof Date d)v=d.toLocalDate().toString(); else if(v instanceof java.sql.Timestamp t)v=t.toLocalDateTime().toString().replace('T',' ');m.put(md.getColumnLabel(i),v);}return m;}; }
}
