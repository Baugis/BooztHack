package com.Warehouse.Simulator.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class EventLogger {

    private final BufferedWriter writer;
    private final Gson gson;

    public EventLogger(String filePath) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(filePath));
        this.gson = new GsonBuilder().create();
    }

    public void log(double simTime, String timestamp, String eventName, Object data) {
        LogEntry entry = new LogEntry(simTime, timestamp, eventName, data);
        try {
            writer.write(gson.toJson(entry));
            writer.newLine();
            writer.flush(); // Flush immediately so logs aren't lost if the app crashes
        } catch (IOException e) {
            System.err.println("Failed to write to simulation log: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (writer != null) writer.close();
        } catch (IOException e) {
            System.err.println("Failed to close simulation log: " + e.getMessage());
        }
    }

    private static class LogEntry {
        
        double simTime;
        String timestamp;
        String event;
        Object data;


        public LogEntry(double simTime, String timestamp, String event, Object data) {
            this.simTime = simTime;
            this.timestamp = timestamp;
            this.event = event;
            this.data = data;
        }
    }
}
