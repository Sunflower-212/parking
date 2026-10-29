package org.zjmproject.utils;
import com.github.pagehelper.PageHelper;
import java.time.LocalDateTime;
public final class PageQuery {
    private PageQuery() {}
    // 集中约束分页大小，并由 PageHelper 只分页紧随其后的 Mapper 查询；不要在两者之间插入其他查询。
    public static void start(Integer page, Integer size) {
        int p=page==null?1:page, s=size==null?20:size;
        if(p<1 || p>1000000 || s<1 || s>100) throw new IllegalArgumentException("分页参数无效，每页限1至100条");
        PageHelper.startPage(p,s);
    }
    public static void range(LocalDateTime from,LocalDateTime to) {
        if(from!=null && to!=null && !from.isBefore(to)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    }
}
