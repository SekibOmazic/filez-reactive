# Getting Started

This project is a simple showcase of a Spring Boot application using Gradle, which handles file uploads and downloads with memory safety in mind. It uses the Spring Reactive Web framework and integrates with AWS S3 for file storage.

### Reference Documentation
Just read the code! The project is designed to be self-explanatory, with clear method names and comments that guide you through the functionality.

### How to Run the Application

With IntelliJ IDEA, you can run the application by executing the `main` method in the `TestFilezApplication` class. Before running, ensure you have the following environment variables set:
- `AWS_ACCESS_KEY_ID=mock-access-key`
- `AWS_SECRET_ACCESS_KEY=mock-secret-key`
- `spring.profiles.active=test`

### Running Tests

To run the tests, you can use the Gradle command:

```bash 
./gradlew clean build
```

