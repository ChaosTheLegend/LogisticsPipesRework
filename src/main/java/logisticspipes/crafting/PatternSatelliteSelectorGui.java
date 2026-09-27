package logisticspipes.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.github.bsideup.jabel.Desugar;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.gui.SubGuiScreen;

@SideOnly(Side.CLIENT)
public class PatternSatelliteSelectorGui extends SubGuiScreen {

    public interface SelectionHandler {

        void selectSatellite(int satelliteId, String satelliteUuid);
    }

    private static final int PREVIOUS_PAGE_BUTTON = 0;
    private static final int NEXT_PAGE_BUTTON = 1;
    private static final int ROW_HEIGHT = 14;
    private static final int VISIBLE_ROWS = 8;

    private final int inputSlot;
    private final int currentSatelliteId;
    private final String currentSatelliteUuid;
    private final PatternSatelliteInfo.SatelliteType satelliteType;
    private final List<PatternSatelliteInfo> satellites;
    private final SelectionHandler handler;
    private GuiTextField searchField;
    private int page;

    public PatternSatelliteSelectorGui(int inputSlot, int currentSatelliteId, List<PatternSatelliteInfo> satellites,
            SelectionHandler handler) {
        this(inputSlot, currentSatelliteId, "", PatternSatelliteInfo.SatelliteType.ITEM, satellites, handler);
    }

    public PatternSatelliteSelectorGui(int inputSlot, int currentSatelliteId, String currentSatelliteUuid,
            PatternSatelliteInfo.SatelliteType satelliteType, List<PatternSatelliteInfo> satellites,
            SelectionHandler handler) {
        super(228, 178, 0, 0);
        this.inputSlot = inputSlot;
        this.currentSatelliteId = currentSatelliteId;
        this.currentSatelliteUuid = currentSatelliteUuid == null ? "" : currentSatelliteUuid;
        this.satelliteType = satelliteType == null ? PatternSatelliteInfo.SatelliteType.ITEM : satelliteType;
        this.satellites = satellites == null ? Collections.emptyList() : new ArrayList<>(satellites);
        this.handler = handler;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        searchField = new GuiTextField(fontRendererObj, guiLeft + 10, guiTop + 22, xSize - 20, 14);
        searchField.setMaxStringLength(64);
        searchField.setFocused(true);
        buttonList
                .add(new SmallGuiButton(PREVIOUS_PAGE_BUTTON, guiLeft + xSize - 52, guiTop + ySize - 17, 20, 10, "<"));
        buttonList.add(new SmallGuiButton(NEXT_PAGE_BUTTON, guiLeft + xSize - 29, guiTop + ySize - 17, 20, 10, ">"));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
    }

    @Override
    protected void renderGuiBackground(int mouseX, int mouseY) {
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        fontRendererObj.drawString(
                "Input " + (inputSlot + 1) + " " + satelliteType.name().toLowerCase(Locale.ROOT) + " satellite",
                guiLeft + 10,
                guiTop + 8,
                0x404040);
        searchField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        List<Row> rows = getFilteredRows();
        clampPage(rows.size());
        int start = page * VISIBLE_ROWS;
        int end = Math.min(rows.size(), start + VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            drawRow(rows.get(i), i - start, mouseX, mouseY);
        }
        String pageText = rows.isEmpty() ? "0/0" : (page + 1) + "/" + getPageCount(rows.size());
        fontRendererObj.drawString(pageText, guiLeft + 10, guiTop + ySize - 16, 0x404040);
    }

    private void drawRow(Row row, int index, int mouseX, int mouseY) {
        int x = guiLeft + 10;
        int y = getRowY(index);
        int color = row.matches(currentSatelliteId, currentSatelliteUuid) ? 0xffd7e8ff : 0xffb8b8b8;
        if (isMouseOverRow(index, mouseX, mouseY)) {
            color = 0xfffff0a8;
        }
        drawRect(x, y, guiLeft + xSize - 10, y + ROW_HEIGHT - 1, 0xff000000);
        drawRect(x + 1, y + 1, guiLeft + xSize - 11, y + ROW_HEIGHT - 2, color);
        String text = fontRendererObj.trimStringToWidth(row.display, xSize - 28);
        fontRendererObj.drawString(text, x + 4, y + 3, 0x202020);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == PREVIOUS_PAGE_BUTTON) {
            changePage(-1);
        } else if (button.id == NEXT_PAGE_BUTTON) {
            changePage(1);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0) {
            int rowIndex = getMouseRow(mouseX, mouseY);
            if (rowIndex >= 0) {
                List<Row> rows = getFilteredRows();
                int index = page * VISIBLE_ROWS + rowIndex;
                if (index < rows.size()) {
                    Row row = rows.get(index);
                    handler.selectSatellite(row.satelliteId, row.satelliteUuid);
                    exitGui();
                    return;
                }
            }
        }
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typed, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typed, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            changePage(-1);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            changePage(1);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            selectFirstVisibleRow();
            return;
        }
        if (searchField.textboxKeyTyped(typed, keyCode)) {
            page = 0;
            return;
        }
        super.keyTyped(typed, keyCode);
    }

    @Override
    public void handleMouseInputSub() {
        super.handleMouseInputSub();
        int wheel = Mouse.getEventDWheel();
        if (wheel > 0) {
            changePage(-1);
        } else if (wheel < 0) {
            changePage(1);
        }
    }

    private void selectFirstVisibleRow() {
        List<Row> rows = getFilteredRows();
        if (rows.isEmpty()) {
            return;
        }
        int index = Math.min(page * VISIBLE_ROWS, rows.size() - 1);
        Row row = rows.get(index);
        handler.selectSatellite(row.satelliteId, row.satelliteUuid);
        exitGui();
    }

    private void changePage(int delta) {
        int pages = getPageCount(getFilteredRows().size());
        page = (page + delta + pages) % pages;
    }

    private void clampPage(int rowCount) {
        page = Math.max(0, Math.min(page, getPageCount(rowCount) - 1));
    }

    private int getPageCount(int rowCount) {
        return Math.max(1, (rowCount + VISIBLE_ROWS - 1) / VISIBLE_ROWS);
    }

    private int getMouseRow(int mouseX, int mouseY) {
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            if (isMouseOverRow(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMouseOverRow(int index, int mouseX, int mouseY) {
        return mouseX >= guiLeft + 10 && mouseX < guiLeft + xSize - 10
                && mouseY >= getRowY(index)
                && mouseY < getRowY(index) + ROW_HEIGHT - 1;
    }

    private int getRowY(int index) {
        return guiTop + 42 + index * ROW_HEIGHT;
    }

    private List<Row> getFilteredRows() {
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        addRowIfMatching(rows, new Row(0, "", "Local inventory", "local none no satellite 0"), query);
        for (PatternSatelliteInfo satellite : satellites) {
            if (satellite.type() != satelliteType) {
                continue;
            }
            addRowIfMatching(
                    rows,
                    new Row(satellite.id(), satellite.uuid(), formatSatellite(satellite), satellite.getSearchText()),
                    query);
        }
        return rows;
    }

    private void addRowIfMatching(List<Row> rows, Row row, String query) {
        if (query.isEmpty() || matches(row.search, query)) {
            rows.add(row);
        }
    }

    private boolean matches(String search, String query) {
        String[] tokens = query.split("\\s+");
        for (String token : tokens) {
            if (!token.isEmpty() && !search.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private String formatSatellite(PatternSatelliteInfo satellite) {
        String prefix = satellite.favorite() ? "* " : "";
        String distance = satellite.distance() >= 0 ? satellite.distance() + "m" : "other dim";
        return prefix + satellite.displayName()
                + " "
                + distance
                + " D"
                + satellite.dimension()
                + " ("
                + satellite.x()
                + ","
                + satellite.y()
                + ","
                + satellite.z()
                + ")";
    }

    @Desugar
    private record Row(int satelliteId, String satelliteUuid, String display, String search) {

        private boolean matches(int currentId, String currentUuid) {
            return (!currentUuid.isEmpty() && currentUuid.equals(satelliteUuid))
                    || (currentUuid.isEmpty() && satelliteId == currentId);
        }
    }
}
