package net.fabricmc.loom.mcp.beta;

import org.csveed.annotations.CsvCell;
import org.csveed.annotations.CsvFile;
import org.csveed.annotations.CsvIgnore;
import org.csveed.bean.ColumnNameMapper;

import java.util.ArrayList;
import java.util.List;

@CsvFile(separator = ',', mappingStrategy = ColumnNameMapper.class)
public class McpClass {
    private String name;
    private String notch;
    private String supername;
    @CsvCell(columnName = "package")
    private String packageName;
    private int side;

    @CsvIgnore
    private List<McpMember> methods = new ArrayList<>();

    @CsvIgnore
    private List<McpMember> fields = new ArrayList<>();

    public List<McpMember> getMethods() {
        return methods;
    }

    public List<McpMember> getFields() {
        return fields;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNotch() {
        return notch;
    }

    public String getFullNotch() {
        return getPackageName().equals("net/minecraft/src") ? getNotch() : (getPackageName() + "/" + getNotch());
    }

    public void setNotch(String notch) {
        this.notch = notch;
    }

    public String getSupername() {
        return supername;
    }

    public void setSupername(String supername) {
        this.supername = supername;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public int getSide() {
        return side;
    }

    public void setSide(int side) {
        this.side = side;
    }
}