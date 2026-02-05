package com.gt120dev.slicedPotatoes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class SlicedPotatoesApplication {

    public static void main(String[] args) throws IOException, InterruptedException {
        SpringApplication.run(SlicedPotatoesApplication.class, args);
        testRun();
    }

    private static void testRun() throws IOException, InterruptedException {
//        KrakenPublicData kpd = new KrakenPublicData();

    }
}
