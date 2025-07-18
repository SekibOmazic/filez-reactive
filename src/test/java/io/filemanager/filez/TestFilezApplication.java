package io.filemanager.filez;

import org.springframework.boot.SpringApplication;

public class TestFilezApplication {

	public static void main(String[] args) {
		SpringApplication.from(FilezApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
