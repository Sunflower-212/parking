package org.zjmproject.ai;

public final class AiSessionTitles {
    private AiSessionTitles() {}
    public static String from(String message) {
        String clean=message.replaceAll("\\s+"," ").trim();
        String title=clean.replaceFirst("^(?:(?:你好|您好|请问|请帮我|麻烦你|帮我|试着问)[，,:：!！。？?\\s]*)+","").trim();
        if(title.isBlank())title=clean;
        int length=title.codePointCount(0,title.length());
        return length>24?title.substring(0,title.offsetByCodePoints(0,24))+"…":title;
    }
}
