package net.fabricmc.loom.mcp.beta;

import org.csveed.annotations.CsvCell;
import org.csveed.annotations.CsvFile;
import org.csveed.bean.ColumnNameMapper;

@CsvFile(separator = ',', mappingStrategy = ColumnNameMapper.class)
public class McpMember {
    private String searge;
    private String name;
    private String notch;
    private String sig;
    private String notchsig;
    private String classname;
    private String classnotch;
    @CsvCell(columnName = "package")
    private String packageName;
    private int side;

    public String getSearge() {
        return searge;
    }

    public void setSearge(String searge) {
        this.searge = searge;
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

    public void setNotch(String notch) {
        this.notch = notch;
    }

    public String getSig() {
        return sig;
    }

    public void setSig(String sig) {
        this.sig = sig;
    }

    public String getNotchsig() {
        return notchsig;
    }

    public void setNotchsig(String notchsig) {
        this.notchsig = notchsig;
    }

    public String getClassname() {
        return classname;
    }

    public void setClassname(String classname) {
        this.classname = classname;
    }

    public String getClassnotch() {
        return classnotch;
    }

    public void setClassnotch(String classnotch) {
        this.classnotch = classnotch;
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