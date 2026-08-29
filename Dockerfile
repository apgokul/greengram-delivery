# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy the maven wrapper and pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure mvnw is executable
RUN chmod +x ./mvnw

# Download dependencies for offline build
RUN ./mvnw dependency:go-offline -B

# Copy the project source
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
