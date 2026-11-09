package org.zjmproject;

import org.apache.ibatis.ognl.Ognl;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Parse every dynamic expression even when null parameters would skip its branch. */
class MapperExpressionTest {
    @Test void everyMapperExpressionParses() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        int checked=0;
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:org/zjmproject/mapper/*.xml")) {
            try(var input=resource.getInputStream()) {
                var nodes=factory.newDocumentBuilder().parse(input).getElementsByTagName("*");
                for(int i=0;i<nodes.getLength();i++) {
                    var node=(org.w3c.dom.Element)nodes.item(i);
                    for(String attribute:new String[]{"test","value"}) if(node.hasAttribute(attribute)) {
                        String expression=node.getAttribute(attribute);
                        assertDoesNotThrow(()->Ognl.parseExpression(expression),resource.getFilename()+": "+expression);checked++;
                    }
                }
            }
        }
        assertTrue(checked>20,"Expected all mapper dynamic expressions to be checked");
    }
}
