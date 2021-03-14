package com.tb24.discordbot;

import com.google.gson.*;
import kotlin.io.FilesKt;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class Blurl {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws DataFormatException, IOException {
        File blurlFile = new File("D:\\Downloads\\master (2).blurl");
        JsonObject blurl = fromFile(blurlFile);
        JsonObject subtitles = JsonParser.parseString(blurl.get("subtitles").getAsString()).getAsJsonObject();
        JsonArray englishSub = subtitles.getAsJsonArray("en");
        File outputFile = new File("out.srt");
        PrintStream f = new PrintStream(outputFile);
        int i = 0;
        for (JsonElement entry_ : englishSub) {
            JsonObject entry = entry_.getAsJsonObject();
            LocalTime startTime = convertBlurlTime(entry.get("startTime").getAsString());
            LocalTime endTime = convertBlurlTime(entry.get("endTime").getAsString());
            String text = entry.get("text").getAsString();
            f.printf("%d\n%s --> %s\n%s\n\n", ++i, startTime, endTime, text);
        }
        f.close();
        System.out.println(outputFile.getAbsolutePath());
        System.exit(0);
    }

    private static LocalTime convertBlurlTime(String in) {
        int days = Integer.parseInt(in.substring(1, 9));
        int hours = Integer.parseInt(in.substring(10, 12));
        int minutes = Integer.parseInt(in.substring(13, 15));
        int seconds = Integer.parseInt(in.substring(16, 18));
        int nanos = Integer.parseInt(in.substring(19));
        return LocalTime.of(days * 24 + hours, minutes, seconds, nanos);
    }

    public static JsonObject fromFile(File f) throws DataFormatException, IOException {
        if (!f.exists() || !FilesKt.getExtension(f).toLowerCase(Locale.ROOT).equals("blurl")) {
            throw new IOException("File does not exist or is not a blurl file");
        }
        byte[] b = FilesKt.readBytes(f);
        if (b[0] != 'b' || b[1] != 'l' || b[2] != 'u' || b[3] != 'l') {
            throw new IOException("Not a blurl file");
        }
        int uncompressedSize = ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
        byte[] uncompressed = new byte[uncompressedSize];
        Inflater inflater = new Inflater();
        inflater.setInput(b, 8, b.length - 8);
        inflater.inflate(uncompressed);
        return JsonParser.parseString(new String(uncompressed, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
