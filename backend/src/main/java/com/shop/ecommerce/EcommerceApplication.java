package com.shop.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Application entry point.
 *
 * <h2>{@code @SpringBootApplication}</h2>
 * A convenience annotation that is exactly three things:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} - marks this as the primary configuration class.</li>
 *   <li>{@code @EnableAutoConfiguration} - lets Boot configure whatever it finds on the
 *       classpath. This is why adding {@code spring-boot-starter-data-jpa} gives you a
 *       working {@code EntityManager} without writing a line of configuration. It is also
 *       the source of most "why does it not work" - Boot configures what is present, and
 *       a missing dependency means missing configuration with no error.</li>
 *   <li>{@code @ComponentScan} - scans this package and everything beneath it. This is
 *       why every class in this project lives under {@code com.shop.ecommerce}. A class
 *       placed in a sibling package is simply never found, and the symptom is a
 *       {@code NoSuchBeanDefinitionException} that looks like a wiring bug rather than a
 *       packaging one.</li>
 * </ul>
 *
 * <h2>{@code @EnableJpaAuditing}</h2>
 * Turns on Spring Data's auditing machinery, which is what populates
 * {@code @CreatedDate} and {@code @LastModifiedDate} on {@link com.shop.ecommerce.entity.BaseEntity}.
 *
 * <p>Without this annotation those two fields stay null, the columns are NOT NULL, and
 * every insert fails - but the error names the column, not the missing annotation, so it
 * reads like a schema problem. The entity also needs
 * {@code @EntityListeners(AuditingEntityListener.class)}; the two are a pair and neither
 * works alone.
 *
 * <h2>{@code exclude = UserDetailsServiceAutoConfiguration.class}</h2>
 * <b>This one line is a security control, not tidiness.</b>
 *
 * <p>When Spring Security is on the classpath and no {@code UserDetailsService} bean
 * exists, Boot helpfully creates an in-memory account named {@code user} whose random
 * password it <em>prints to the log</em> as {@code Using generated security password: ...}.
 * That account authenticates against a map, not against the {@code users} table. It is
 * code nobody on this project wrote, covered by no test, and its credential ends up in
 * a log aggregator.
 *
 * <p>Excluding the autoconfiguration removes the mechanism. Because it is a
 * compile-checked class reference, a Boot upgrade that renames or moves the class breaks
 * the build instead of silently re-opening the door.
 *
 * <p>{@code SecurityAutoConfiguration} is deliberately <b>not</b> excluded: it backs off
 * on its own once a {@code SecurityFilterChain} bean exists, and excluding it would
 * remove the filter-chain machinery we need.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableJpaAuditing
public class EcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}
