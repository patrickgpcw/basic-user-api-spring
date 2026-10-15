package com.example.basicuserapispring.user;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String firstName;
    @Column(nullable = false, length = 100)
    private String lastName;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(length = 30)
    private String telephone;
    @Column(nullable = false, length = 255)
    private String passwordHash;

    protected User() {}

    public User(String firstName, String lastName, String email, String telephone, String passwordHash) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.telephone = telephone;
        this.passwordHash = passwordHash;
    }
    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getTelephone() { return telephone; }
    public String getPasswordHash() { return passwordHash; }
}
