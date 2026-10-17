package org.zjmproject.pojo;
import java.util.List;

public class PlateInfo {
    private WordsResult words_result;
    private String log_id;

    public WordsResult getWordsResult() {
        return words_result;
    }

    public void setWordsResult(WordsResult wordsResult) {
        this.words_result = wordsResult;
    }

    public String getLogId() {
        return log_id;
    }

    public void setLogId(String logId) {
        this.log_id = logId;
    }

    public static class WordsResult {
        private String number;
        private List<Vertex> vertexes_location;
        private String color;
        private List<Double> probability;

        public String getNumber() {
            return number;
        }

        public void setNumber(String number) {
            this.number = number;
        }

        public List<Vertex> getVertexesLocation() {
            return vertexes_location;
        }

        public void setVertexesLocation(List<Vertex> vertexesLocation) {
            this.vertexes_location = vertexesLocation;
        }

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }

        public List<Double> getProbability() {
            return probability;
        }

        public void setProbability(List<Double> probability) {
            this.probability = probability;
        }
    }

    public static class Vertex {
        private int x;
        private int y;

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }
    }
}
