import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * VtxConfig - offline Betaflight VTX configuration generator.
 * Java 17+; no external runtime is required.
 */
public class VtxApp extends JFrame {
    static final String APP_NAME = "VtxConfig";
    static final int JSON_VERSION = 2;
    static final String[] UARTS = {"UART1","UART2","UART3","UART4","UART5","UART6"};
    static final String[] PROTOCOLS = {"IRC Tramp","TBS SmartAudio 2.0","TBS SmartAudio 2.1"};
    static final String[] AUXES = {"AUX1","AUX2","AUX3","AUX4","AUX5","AUX6","AUX7","AUX8","AUX9","AUX10","AUX11","AUX12"};
    static final String[] BAND_LABEL = {"0 — USER","1 — A","2 — B","3 — E","4 — F","5 — R"};
    // Common Betaflight table; E3-E8 are the current documented common values.
    static final int[][] FREQS = {
        {5865,5845,5825,5805,5785,5765,5745,5725},
        {5733,5752,5771,5790,5809,5828,5847,5866},
        {5705,5685,5665,5645,5885,5905,5925,5945},
        {5740,5760,5780,5800,5820,5840,5860,5880},
        {5658,5695,5732,5769,5806,5843,5880,5917}
    };
    static final String[] BAND_LETTER = {"A","B","E","F","R"};

    record BandStep(String aux, int band, int channel, int start, int end) {}

    static final class Config {
        String name = "Нова конфігурація";
        String template = "";
        String uart = "UART1";
        String protocol = "TBS SmartAudio 2.0";
        String aux = "AUX3";
        int dband = 5, dchan = 1;
        int[] powers = {0, 1, 2};
        String selectedVtx = "";
        boolean incTable = true;
        boolean includePortSetup = false;
        List<BandStep> steps = defaultSteps();

        static List<BandStep> defaultSteps() {
            String a = "AUX2";
            return new ArrayList<>(List.of(
                new BandStep(a,1,1,900,975), new BandStep(a,2,1,975,1050),
                new BandStep(a,3,1,1050,1125), new BandStep(a,4,1,1125,1200),
                new BandStep(a,5,1,1200,1275), new BandStep(a,0,1,1275,1350)));
        }
        Config copy() {
            Config c = new Config();
            c.name=name; c.selectedVtx=selectedVtx; c.template=template; c.uart=uart; c.protocol=protocol; c.aux=aux;
            c.dband=dband; c.dchan=dchan; c.powers=powers.clone(); c.incTable=incTable;
            c.includePortSetup=includePortSetup; c.steps=new ArrayList<>(steps); return c;
        }
    }

    final List<Config> configs = new ArrayList<>();
    int idx=0, power=2, view=0;
    boolean loading=false;
    static Path dataFile;

    final JTextField fName=new JTextField(25), fTemplate=new JTextField(25), fCom=new JTextField("COM3",10);
    final JComboBox<String> fUart=new JComboBox<>(UARTS), fProtocol=new JComboBox<>(PROTOCOLS), fAux=new JComboBox<>(AUXES);
    final JComboBox<String> fBand=new JComboBox<>(BAND_LABEL), fChan=new JComboBox<>(new String[]{"1","2","3","4","5","6","7","8"});
    final JSpinner sP1=new JSpinner(new SpinnerNumberModel(0,0,7,1)), sP2=new JSpinner(new SpinnerNumberModel(1,0,7,1)), sP3=new JSpinner(new SpinnerNumberModel(2,0,7,1));
    final JCheckBox fTable=new JCheckBox("Увімкнути VTX-таблицю",true), fPort=new JCheckBox("Додати налаштування VTX-порту",false);
    final JCheckBox fSave=new JCheckBox("Після відправки виконати save",true);
    final DefaultListModel<String> listModel=new DefaultListModel<>(); final JList<String> list=new JList<>(listModel);
    final JComboBox<String>[] stAux=new JComboBox[6]; final JComboBox<String>[] stBand=new JComboBox[6]; final JComboBox<String>[] stChan=new JComboBox[6];
    final JSpinner[] stStart=new JSpinner[6], stEnd=new JSpinner[6];
    final JLabel selectedPhoto=new JLabel("Додайте фото у каталозі",SwingConstants.CENTER);
    final JLabel selectedTitle=new JLabel("VTX не вибрано");
    final List<Device> devices=new ArrayList<>();
    static Path catalogFile, photoDir;
    record Device(String maker,String model,String protocol,String image){
        public String toString(){return maker+" · "+model;}
    }
    String selectedVtx="";
    volatile String detectedPort="", detectedVersion="";
    String importedDump=null; String importedDumpName="";
    final JTextArea out=new JTextArea(22,52); final JButton[] tabs=new JButton[3]; final JLabel status=new JLabel(" ");

    public VtxApp() {
        super(APP_NAME+" 2.2.1 — офлайн-генератор"); setDefaultCloseOperation(EXIT_ON_CLOSE); setLayout(new BorderLayout(8,8));
        add(collectionPanel(),BorderLayout.WEST); add(centerPanel(),BorderLayout.CENTER); add(status,BorderLayout.SOUTH);
        initCatalog();
        load(); if(configs.isEmpty()) configs.add(new Config()); refreshList(); select(Math.min(idx,configs.size()-1));
        setSize(1450,850); setLocationRelativeTo(null); setMinimumSize(new Dimension(1120,740)); updateSelectedPhoto();
    }

    JPanel collectionPanel(){
        JPanel left=new JPanel(new BorderLayout(5,5)); left.setBorder(new TitledBorder("Мої конфігурації")); left.setPreferredSize(new Dimension(225,640));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!loading&&list.getSelectedIndex()>=0)select(list.getSelectedIndex());});
        left.add(new JScrollPane(list),BorderLayout.CENTER);
        JPanel b=new JPanel(new GridLayout(3,2,4,4)); b.add(btn("Нова",this::newCfg)); b.add(btn("Дубль",this::dupCfg)); b.add(btn("Видалити",this::delCfg)); b.add(btn("Експорт",this::exportAll)); b.add(btn("Імпорт",this::importAll)); b.add(btn("Тека даних",this::openDataDir)); left.add(b,BorderLayout.SOUTH); return left;
    }
    JPanel centerPanel(){
        JPanel mid=new JPanel(new BorderLayout(5,5)); mid.setBorder(new TitledBorder("Параметри")); JPanel top=new JPanel(new BorderLayout(6,6));top.add(selectedPanel(),BorderLayout.NORTH);top.add(form(),BorderLayout.CENTER);mid.add(top,BorderLayout.NORTH); mid.add(stepsTable(),BorderLayout.CENTER);
        JPanel right=new JPanel(new BorderLayout(5,5)); right.setBorder(new TitledBorder("Результат")); JPanel tabsP=new JPanel(new GridLayout(1,3,4,4));
        String[] ns={"CLI","VTX table CLI","VTX table JSON"}; for(int i=0;i<3;i++){final int k=i; tabs[i]=btn(ns[i],()->setView(k));tabsP.add(tabs[i]);} JPanel rightTop=new JPanel(new BorderLayout(4,4));
        rightTop.add(tabsP,BorderLayout.NORTH);
        JPanel fcBar=new JPanel(new GridLayout(4,2,5,4));
        fcBar.add(btn("ЗНАЙТИ FC (USB)",this::detectFc));
        fcBar.add(btn("BACKUP FC (USB)",this::backupFc));
        fcBar.add(btn("ІМПОРТ ДАМПА",this::importFcDump));
        fcBar.add(btn("ПЕРЕГЛЯД ЗМІН",this::previewFcChanges));
        fcBar.add(btn("ЗЧИТАТИ FC І ПОРІВНЯТИ",this::compareLiveFc));
        fcBar.add(btn("Відправити на FC",this::sendToFc));
        rightTop.add(fcBar,BorderLayout.SOUTH);
        right.add(rightTop,BorderLayout.NORTH);
        out.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12)); out.setEditable(false); right.add(new JScrollPane(out),BorderLayout.CENTER);
        JPanel rb=new JPanel(new FlowLayout(FlowLayout.LEFT,4,4)); rb.add(btn("Копіювати",this::copyOut)); rb.add(btn("Зберегти файл",this::saveCurrent)); rb.add(btn("Зберегти всі три",this::saveAllThree)); rb.add(btn("Каталог з фото",this::showCatalog)); rb.add(btn("Темна тема",()->setDarkTheme(true))); rb.add(btn("Світла тема",()->setDarkTheme(false))); right.add(rb,BorderLayout.SOUTH);
        JPanel c=new JPanel(new GridLayout(1,2,8,8)); c.add(mid); c.add(right); return c;
    }
    JPanel form(){
        JPanel p=new JPanel(new GridBagLayout()); GridBagConstraints g=new GridBagConstraints(); g.insets=new Insets(3,7,3,7); g.anchor=GridBagConstraints.WEST; int y=0;
        addRow(p,g,y++,"Назва:",fName); addRow(p,g,y++,"Шаблон VTX:",fTemplate); addRow(p,g,y++,"Послідовний порт:",fUart); addRow(p,g,y++,"Протокол:",fProtocol); addRow(p,g,y++,"AUX керування VTX:",fAux); addRow(p,g,y++,"Типовий діапазон:",fBand); addRow(p,g,y++,"Типовий канал:",fChan);
        g.gridy=y++; g.gridx=0;p.add(new JLabel("Стани потужності:"),g); JPanel pw=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));pw.add(sP1);pw.add(sP2);pw.add(sP3);g.gridx=1;p.add(pw,g);
        g.gridy=y++;g.gridx=0;p.add(new JLabel("VTX-таблиця:"),g);g.gridx=1;p.add(fTable,g);
        g.gridy=y++;g.gridx=0;p.add(new JLabel("Порт у CLI:"),g);g.gridx=1;p.add(fPort,g);
        Runnable live=()->{if(!loading){store();render();}}; fName.getDocument().addDocumentListener(new SimpleDoc(live));fTemplate.getDocument().addDocumentListener(new SimpleDoc(live));
        for(JComboBox<?> c:List.of(fUart,fProtocol,fAux,fBand,fChan))c.addActionListener(e->live.run()); for(JSpinner s:List.of(sP1,sP2,sP3))s.addChangeListener(e->live.run()); fTable.addActionListener(e->live.run());fPort.addActionListener(e->live.run());
        return p;
    }
    static void addRow(JPanel p,GridBagConstraints g,int y,String label,JComponent c){g.gridy=y;g.gridx=0;p.add(new JLabel(label),g);g.gridx=1;p.add(c,g);}
    JPanel stepsTable(){
        JPanel p=new JPanel(new GridBagLayout());p.setBorder(new TitledBorder("Керування діапазоном — 6 позицій"));GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(2,5,2,5);String[] h={"AUX","Діапазон","Канал","Початок","Кінець"};for(int c=0;c<h.length;c++){g.gridx=c;g.gridy=0;p.add(new JLabel(h[c]),g);}
        Runnable live=()->{if(!loading){store();render();}};for(int r=0;r<6;r++){stAux[r]=new JComboBox<>(AUXES);stBand[r]=new JComboBox<>(BAND_LABEL);stChan[r]=new JComboBox<>(new String[]{"1","2","3","4","5","6","7","8"});stStart[r]=spin(900);stEnd[r]=spin(1000);JComponent[] row={stAux[r],stBand[r],stChan[r],stStart[r],stEnd[r]};for(int c=0;c<row.length;c++){g.gridx=c;g.gridy=r+1;p.add(row[c],g);if(row[c] instanceof JComboBox<?> cb)cb.addActionListener(e->live.run());else ((JSpinner)row[c]).addChangeListener(e->live.run());}}return p;
    }
    static JSpinner spin(int v){JSpinner s=new JSpinner(new SpinnerNumberModel(v,800,2200,25));s.setEditor(new JSpinner.NumberEditor(s,"#"));return s;}
    static JButton btn(String t,Runnable r){JButton b=new JButton(t);b.addActionListener(e->r.run());return b;}
    static class SimpleDoc implements DocumentListener{final Runnable r;SimpleDoc(Runnable r){this.r=r;}public void insertUpdate(DocumentEvent e){r.run();}public void removeUpdate(DocumentEvent e){r.run();}public void changedUpdate(DocumentEvent e){r.run();}}

    Config collect(){Config c=new Config();c.selectedVtx=selectedVtx;c.name=fName.getText().trim().isEmpty()?"Без назви":fName.getText().trim();c.template=fTemplate.getText().trim();c.uart=(String)fUart.getSelectedItem();c.protocol=(String)fProtocol.getSelectedItem();c.aux=(String)fAux.getSelectedItem();c.dband=bandCode((String)fBand.getSelectedItem(),5);c.dchan=Integer.parseInt((String)fChan.getSelectedItem());c.powers=new int[]{(int)sP1.getValue(),(int)sP2.getValue(),(int)sP3.getValue()};c.incTable=fTable.isSelected();c.includePortSetup=fPort.isSelected();c.steps=new ArrayList<>();for(int r=0;r<6;r++)c.steps.add(new BandStep((String)stAux[r].getSelectedItem(),bandCode((String)stBand[r].getSelectedItem(),0),Integer.parseInt((String)stChan[r].getSelectedItem()),(int)stStart[r].getValue(),(int)stEnd[r].getValue()));return c;}
    static int bandCode(String s,int d){try{return Integer.parseInt(s.split(" ")[0]);}catch(Exception e){return d;}}
    static int auxIndex(String a){try{return Integer.parseInt(a.replace("AUX",""))-1;}catch(Exception e){return 0;}}
    void select(int i){if(i<0||i>=configs.size())return;idx=i;loading=true;Config c=configs.get(i);selectedVtx=c.selectedVtx;fName.setText(c.name);fTemplate.setText(c.template);fUart.setSelectedItem(c.uart);fProtocol.setSelectedItem(c.protocol);fAux.setSelectedItem(c.aux);fBand.setSelectedItem(BAND_LABEL[Math.max(0,Math.min(5,c.dband))]);fChan.setSelectedItem(String.valueOf(c.dchan));sP1.setValue(c.powers[0]);sP2.setValue(c.powers[1]);sP3.setValue(c.powers[2]);fTable.setSelected(c.incTable);fPort.setSelected(c.includePortSetup);for(int r=0;r<6;r++){BandStep s=c.steps.get(r);stAux[r].setSelectedItem(s.aux());stBand[r].setSelectedItem(BAND_LABEL[Math.max(0,Math.min(5,s.band()))]);stChan[r].setSelectedItem(String.valueOf(s.channel()));stStart[r].setValue(s.start());stEnd[r].setValue(s.end());}loading=false;list.setSelectedIndex(i);render();updateSelectedPhoto();}

    String buildCli(Config c){StringBuilder b=new StringBuilder();b.append("# VTX config: ").append(c.name).append('\n');b.append("# template: ").append(c.template.isEmpty()?"—":c.template).append(" | port: ").append(c.uart).append(" | protocol: ").append(c.protocol).append("\n\n");
        if(c.includePortSetup){
            b.append("# PORT SETUP: verified for Betaflight <= 2025.12; this replaces the function mask on the selected port.\n");
            int fn=c.protocol.startsWith("IRC")?8192:2048;
            b.append("serial ").append(c.uart).append(' ').append(fn).append(" 115200 57600 0 115200\n\n");
            b.append("# For Betaflight 2026.12+, do not paste the serial line above; use:\n");
            b.append("# set vtx_uart = ").append(c.uart).append("\n\n");
        }
        b.append("set vtx_band = ").append(c.dband).append('\n');b.append("set vtx_channel = ").append(c.dchan).append('\n');b.append("set vtx_power = ").append(c.powers[Math.max(0,Math.min(power,c.powers.length-1))]).append("\n");b.append("# power states: 1=").append(c.powers[0]).append(" 2=").append(c.powers[1]).append(" 3=").append(c.powers[2]).append("\n\n");
        b.append("# 6-position band control\n");for(int i=0;i<c.steps.size();i++){BandStep s=c.steps.get(i);b.append("vtx ").append(i).append(' ').append(auxIndex(s.aux())).append(' ').append(s.band()).append(' ').append(s.channel()).append(' ').append(s.band()==0?0:c.powers[Math.min(power,c.powers.length-1)]).append(' ').append(s.start()).append(' ').append(s.end()).append('\n');}
        b.append("\n# power control\n");int base=c.steps.size();b.append("vtx ").append(base).append(' ').append(auxIndex(c.aux)).append(" 0 0 ").append(c.powers[0]).append(" 900 1100\n");b.append("vtx ").append(base+1).append(' ').append(auxIndex(c.aux)).append(" 0 0 ").append(c.powers[1]).append(" 1100 1400\n");b.append("vtx ").append(base+2).append(' ').append(auxIndex(c.aux)).append(" 0 0 ").append(c.powers[2]).append(" 1400 2100\n");b.append("save\n");return b.toString();}

    String buildTableCli(){Config c=collect();StringBuilder b=new StringBuilder();b.append("vtxtable bands 5\nvtxtable channels 8\n");if(c.protocol.startsWith("IRC")){b.append("vtxtable powerlevels 5\nvtxtable powervalues 25 100 200 400 600\nvtxtable powerlabels 25 100 200 400 600\n");}else if(c.protocol.endsWith("2.1")){b.append("# SmartAudio 2.1 power values are model-specific. Query: vtx_info\n# Example only (verify against the VTX manufacturer / vtx_info before use):\n");b.append("vtxtable powerlevels 4\nvtxtable powervalues 14 20 26 30\nvtxtable powerlabels 25 100 400 800").append("\n");}else{b.append("vtxtable powerlevels 4\nvtxtable powervalues 0 1 2 3\nvtxtable powerlabels 25 200 500 800\n");}for(int code=1;code<=5;code++){String l=BAND_LETTER[code-1];b.append("vtxtable band ").append(code).append(" BOSCAM_").append(l).append(' ').append(l).append(" CUSTOM");for(int f:FREQS[code-1])b.append(' ').append(f);b.append('\n');}return b.toString();}
    String buildTableJson(Config c){StringBuilder b=new StringBuilder();String[] labels=c.protocol.startsWith("IRC")?new String[]{"25","100","200","400","600"}:new String[]{"25","200","500","800"};int[] vals=c.protocol.startsWith("IRC")?new int[]{25,100,200,400,600}:c.protocol.endsWith("2.1")?new int[]{14,20,26,30}:new int[]{0,1,2,3};b.append("{\n  \"description\": \"").append(Json.escape(c.template.isEmpty()?c.name:c.template)).append("\",\n  \"version\": \"1.0\",\n  \"vtx_table\": {\n    \"bands_list\": [\n");for(int code=1;code<=5;code++){String l=BAND_LETTER[code-1];b.append("      { \"name\": \"BOSCAM_").append(l).append("\", \"letter\": \"").append(l).append("\", \"isFactoryBand\": false, \"frequencies\": [");for(int i=0;i<8;i++){if(i>0)b.append(", ");b.append(FREQS[code-1][i]);}b.append("] }").append(code<5?",\n":"\n");}b.append("    ],\n    \"power_levels_list\": [\n");for(int i=0;i<vals.length;i++)b.append("      { \"value\": ").append(vals[i]).append(", \"label\": \"").append(labels[i]).append("\" }").append(i+1<vals.length?",\n":"\n");b.append("    ],\n    \"band\": ").append(c.dband).append(",\n    \"channel\": ").append(c.dchan).append(",\n    \"power_level\": ").append(c.powers[Math.min(power,c.powers.length-1)]).append("\n  }\n}\n");return b.toString();}
    void setView(int k){view=k;for(int i=0;i<3;i++)tabs[i].setEnabled(i!=k);render();}void render(){Config c=collect();out.setText(view==1?buildTableCli():view==2?buildTableJson(c):buildCli(c));out.setCaretPosition(0);}

    void refreshList(){listModel.clear();for(Config c:configs)listModel.addElement(c.name);}void store(){if(loading||idx<0||idx>=configs.size())return;configs.set(idx,collect());persist();loading=true;int s=idx;refreshList();list.setSelectedIndex(s);loading=false;}
    void newCfg(){String n=JOptionPane.showInputDialog(this,"Назва конфігурації:","Нова конфігурація");if(n==null||n.isBlank())return;Config c=new Config();c.name=n.trim();configs.add(c);persist();refreshList();select(configs.size()-1);}void dupCfg(){store();Config c=configs.get(idx).copy();c.name+=" (копія)";configs.add(c);persist();refreshList();select(configs.size()-1);}void delCfg(){if(configs.size()<=1){JOptionPane.showMessageDialog(this,"Має залишитися хоча б одна конфігурація.");return;}if(JOptionPane.showConfirmDialog(this,"Видалити «"+configs.get(idx).name+"»?","Підтвердження",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;configs.remove(idx);persist();refreshList();select(Math.min(idx,configs.size()-1));}
    void copyOut(){StringSelection s=new StringSelection(out.getText());Toolkit.getDefaultToolkit().getSystemClipboard().setContents(s,s);status.setText("Скопійовано в буфер обміну.");}
    // Native Windows file dialogs use the OS palette, independently of the dark Swing theme.
    static File chooseFile(Window owner, boolean save, String initialName, String photoExtensions){
        Frame frame=owner instanceof Frame f?f:null;
        FileDialog dlg=new FileDialog(frame,save?"Зберегти файл":"Відкрити файл",save?FileDialog.SAVE:FileDialog.LOAD);
        if(initialName!=null)dlg.setFile(initialName);
        if(photoExtensions!=null)dlg.setFilenameFilter((dir,name)->name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp)"));
        dlg.setVisible(true);
        if(dlg.getFile()==null)return null;
        return new File(dlg.getDirectory(),dlg.getFile());
    }
    // A directory picker is still Swing; temporarily restore the OS defaults for its modal lifetime.
    static File chooseDirectory(Component parent){
        javax.swing.UIDefaults defaults=UIManager.getDefaults();
        Map<String,Object> previous=new HashMap<>();
        String[] keys={"Panel.background","Viewport.background","OptionPane.background","CheckBox.background",
            "RadioButton.background","ScrollPane.background","Label.foreground","CheckBox.foreground",
            "RadioButton.foreground","ComboBox.foreground","ComboBox.selectionForeground","List.foreground",
            "List.selectionForeground","TextField.foreground","TextArea.foreground","Spinner.foreground",
            "FormattedTextField.foreground","OptionPane.messageForeground","TitledBorder.titleColor",
            "Menu.foreground","MenuItem.foreground","ComboBox.background","ComboBox.selectionBackground",
            "List.background","TextField.background","TextArea.background","Spinner.background",
            "FormattedTextField.background","List.selectionBackground","TextField.caretForeground",
            "TextArea.caretForeground"};
        for(String key:keys){previous.put(key,UIManager.get(key));UIManager.put(key,null);}
        try{
            JFileChooser chooser=new JFileChooser();
            chooser.setDialogTitle("Оберіть папку для збереження");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            return chooser.showSaveDialog(parent)==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;
        }finally{
            for(String key:keys)UIManager.put(key,previous.get(key));
        }
    }
    void saveCurrent(){String[] n={"vtx_diff_all.txt","vtx_table.txt","vtx_table.json"};File chosen=chooseFile(this,true,n[view],null);if(chosen==null)return;try{Files.writeString(chosen.toPath(),out.getText(),StandardCharsets.UTF_8);status.setText("Збережено: "+chosen);}catch(IOException e){error(e);}}
    void saveAllThree(){File chosen=chooseDirectory(this);if(chosen==null)return;Path d=chosen.toPath();Config c=collect();try{Files.writeString(d.resolve("vtx_diff_all.txt"),buildCli(c),StandardCharsets.UTF_8);Files.writeString(d.resolve("vtx_table.txt"),buildTableCli(),StandardCharsets.UTF_8);Files.writeString(d.resolve("vtx_table.json"),buildTableJson(c),StandardCharsets.UTF_8);status.setText("Три файли збережено у "+d);}catch(IOException e){error(e);}}
    void exportAll(){store();File chosen=chooseFile(this,true,"vtx_configs.json",null);if(chosen==null)return;try{Files.writeString(chosen.toPath(),Json.stringify(configs),StandardCharsets.UTF_8);status.setText("Колекцію збережено: "+chosen);}catch(IOException e){error(e);}}
    void importAll(){File chosen=chooseFile(this,false,null,null);if(chosen==null)return;try{String raw=Files.readString(chosen.toPath(),StandardCharsets.UTF_8);List<Config> incoming=Json.toConfigs(raw);Set<String> known=new HashSet<>();for(Config c:configs)known.add(c.name);int added=0;for(Config c:incoming)if(known.add(c.name)){configs.add(c);added++;}persist();refreshList();select(configs.size()-1);status.setText("Імпортовано: "+added);}catch(Exception e){error(e);}}
    void openDataDir(){try{Desktop.getDesktop().open(dataFile.getParent().toFile());}catch(Exception e){JOptionPane.showMessageDialog(this,"Тека даних:\n"+dataFile.getParent());}}void error(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"Помилка",JOptionPane.ERROR_MESSAGE);}

    JPanel selectedPanel(){
        JPanel card=new JPanel(new BorderLayout(10,4));card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(49,70,96),1,true),BorderFactory.createEmptyBorder(8,12,8,12)));
        selectedPhoto.setPreferredSize(new Dimension(145,105)); selectedPhoto.setOpaque(true);selectedPhoto.setBackground(new Color(19,29,44));
        selectedPhoto.setForeground(new Color(160,180,200));card.add(selectedPhoto,BorderLayout.WEST);
        JPanel info=new JPanel(new BorderLayout(4,4));JLabel caption=new JLabel("ОБРАНИЙ ПЕРЕДАВАЧ");caption.setForeground(new Color(94,190,255));info.add(caption,BorderLayout.NORTH);
        selectedTitle.setFont(selectedTitle.getFont().deriveFont(Font.BOLD,16f));info.add(selectedTitle,BorderLayout.CENTER);
        info.add(btn("Відкрити каталог / змінити фото",this::showCatalog),BorderLayout.SOUTH);card.add(info,BorderLayout.CENTER);
        return card;
    }
    void initCatalog(){
        Path dir=resolveDataFile().getParent();catalogFile=dir.resolve("vtx_catalog.tsv");photoDir=dir.resolve("photos");
        try{Files.createDirectories(photoDir);}catch(IOException ex){status.setText(ex.getMessage());}
        if(Files.exists(catalogFile))try{
            for(String line:Files.readAllLines(catalogFile,StandardCharsets.UTF_8)){
                String[] x=line.split("\t",-1);if(x.length==4)devices.add(new Device(unb64(x[0]),unb64(x[1]),unb64(x[2]),unb64(x[3])));
            }
        }catch(Exception ex){status.setText("Помилка каталогу: "+ex.getMessage());}
        if(devices.isEmpty()&&!Files.exists(catalogFile)){
            devices.add(new Device("TBS","Unify Pro32 HV","Перевірити за документацією",""));
            devices.add(new Device("RushFPV","Tank Solo","Перевірити за документацією",""));
            devices.add(new Device("AKK","Вкажіть модель","Перевірити за документацією",""));
            devices.add(new Device("SpeedyBee","Вкажіть модель","Перевірити за документацією",""));
            saveCatalog();
        }
    }
    static String b64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    static String unb64(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    void saveCatalog(){try{StringBuilder b=new StringBuilder();for(Device d:devices)b.append(b64(d.maker())).append('\t').append(b64(d.model())).append('\t').append(b64(d.protocol())).append('\t').append(b64(d.image())).append('\n');Files.writeString(catalogFile,b.toString(),StandardCharsets.UTF_8);}catch(IOException ex){error(ex);}}
    static ImageIcon thumbnail(String path,int w,int h){
        if(path==null||path.isBlank())return null;
        try{BufferedImage img=ImageIO.read(Path.of(path).toFile());if(img==null)return null;
            double scale=Math.min((double)w/img.getWidth(),(double)h/img.getHeight());int iw=Math.max(1,(int)(img.getWidth()*scale)),ih=Math.max(1,(int)(img.getHeight()*scale));
            return new ImageIcon(img.getScaledInstance(iw,ih,Image.SCALE_SMOOTH));
        }catch(Exception ex){return null;}
    }
    void updateSelectedPhoto(){
        Device chosen=null;for(Device d:devices)if(d.toString().equals(selectedVtx)){chosen=d;break;}
        selectedTitle.setText(chosen==null?"Оберіть модель у каталозі":chosen.toString());
        ImageIcon icon=chosen==null?null:thumbnail(chosen.image(),145,105);
        selectedPhoto.setIcon(icon);selectedPhoto.setText(icon==null?"Немає фото":"");
    }
    void showCatalog(){
        JDialog dialog=new JDialog(this,"Каталог VTX — фотографії",true);dialog.setSize(920,650);dialog.setLocationRelativeTo(this);
        DefaultListModel<Device> model=new DefaultListModel<>();for(Device d:devices)model.addElement(d);
        JList<Device> devList=new JList<>(model);devList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextField search=new JTextField();JPanel left=new JPanel(new BorderLayout(5,5));left.add(new JLabel("Пошук за виробником або моделлю:"),BorderLayout.NORTH);
        JPanel searchPanel=new JPanel(new BorderLayout());searchPanel.add(search,BorderLayout.CENTER);left.add(searchPanel,BorderLayout.BEFORE_FIRST_LINE);
        left.add(new JScrollPane(devList),BorderLayout.CENTER);left.setPreferredSize(new Dimension(300,400));
        JLabel image=new JLabel("Фото ще не додано",SwingConstants.CENTER);image.setPreferredSize(new Dimension(440,350));image.setOpaque(true);image.setBackground(new Color(19,29,44));
        JLabel info=new JLabel("Оберіть передавач");JPanel detail=new JPanel(new BorderLayout(5,5));detail.add(image,BorderLayout.CENTER);detail.add(info,BorderLayout.SOUTH);
        Runnable refresh=()->{Device d=devList.getSelectedValue();if(d==null){image.setIcon(null);image.setText("Оберіть VTX");info.setText(" ");return;}ImageIcon icon=thumbnail(d.image(),440,350);image.setIcon(icon);image.setText(icon==null?"Фото ще не додано":"");info.setText("<html><b>"+html(d.toString())+"</b><br>Протокол: "+html(d.protocol())+"</html>");};
        devList.addListSelectionListener(e->{if(!e.getValueIsAdjusting())refresh.run();});
        search.getDocument().addDocumentListener(new SimpleDoc(()->{String q=search.getText().toLowerCase(Locale.ROOT);model.clear();for(Device d:devices)if(d.toString().toLowerCase(Locale.ROOT).contains(q))model.addElement(d);}));
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,6));
        actions.add(btn("Обрати для конфігурації",()->{Device d=devList.getSelectedValue();if(d==null)return;selectedVtx=d.toString();fTemplate.setText(d.model());store();updateSelectedPhoto();dialog.dispose();}));
        actions.add(btn("Додати модель",()->{JTextField maker=new JTextField(),name=new JTextField(),proto=new JTextField("Не перевірено");JPanel form=new JPanel(new GridLayout(0,1,3,3));form.add(new JLabel("Виробник:"));form.add(maker);form.add(new JLabel("Модель:"));form.add(name);form.add(new JLabel("Протокол (якщо відомий):"));form.add(proto);if(JOptionPane.showConfirmDialog(dialog,form,"Нова модель",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;if(maker.getText().isBlank()||name.getText().isBlank()){JOptionPane.showMessageDialog(dialog,"Вкажіть виробника й модель");return;}Device d=new Device(maker.getText().trim(),name.getText().trim(),proto.getText().trim(),"");devices.add(d);saveCatalog();model.addElement(d);devList.setSelectedValue(d,true);}));
        actions.add(btn("Додати / замінити фото",()->{Device d=devList.getSelectedValue();if(d==null)return;File chosen=chooseFile(dialog,false,null,"photos");if(chosen==null)return;try{Path source=chosen.toPath();if(ImageIO.read(source.toFile())==null)throw new IOException("Формат зображення не підтримується. Використайте JPG або PNG.");String ext=source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")?".png":".jpg";Path dest=Files.createTempFile(photoDir,"vtx_",ext);Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);int pos=devices.indexOf(d);Device updated=new Device(d.maker(),d.model(),d.protocol(),dest.toString());devices.set(pos,updated);int selected=devList.getSelectedIndex();model.set(selected,updated);devList.setSelectedIndex(selected);saveCatalog();refresh.run();updateSelectedPhoto();}catch(Exception ex){JOptionPane.showMessageDialog(dialog,ex.getMessage(),"Помилка фото",JOptionPane.ERROR_MESSAGE);}}));
        actions.add(btn("Видалити модель",()->{Device d=devList.getSelectedValue();if(d==null)return;if(JOptionPane.showConfirmDialog(dialog,"Видалити «"+d+"»?","Підтвердження",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;devices.remove(d);model.removeElement(d);saveCatalog();if(selectedVtx.equals(d.toString())){selectedVtx="";store();updateSelectedPhoto();}}));
        actions.add(btn("Закрити",dialog::dispose));
        dialog.setLayout(new BorderLayout(8,8));dialog.add(left,BorderLayout.WEST);dialog.add(detail,BorderLayout.CENTER);dialog.add(actions,BorderLayout.SOUTH);if(!model.isEmpty())devList.setSelectedIndex(0);setDarkTheme(true);dialog.setVisible(true);
    }
    static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    static final String CATALOG="""
VTX — протоколи / типи

IRC Tramp
- Тип: аналоговий VTX control
- CLI power values: 25 100 200 400 600 (приклад документації Betaflight)

TBS SmartAudio 2.0
- CLI power values: 0 1 2 3
- Типові labels: 25 200 500 800

TBS SmartAudio 2.1
- Power values залежать від конкретної моделі VTX; приклад: 14 20 26 30 dBm.

RX — протоколи
- ExpressLRS / TBS Crossfire / Tracer: CRSF
- FrSky: SBUS / FPort
- Spektrum: Spektrum1024/2048 / SRXL2
- FlySky: IBUS

Каталог навмисно не містить прив'язки моделі до UART/pin: це залежить від конкретного flight controller.
""";

    void detectFc(){
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")){
            JOptionPane.showMessageDialog(this,"Автопошук COM доступний у Windows.");return;
        }
        detectedPort=""; detectedVersion="";
        status.setText("Пошук політного контролера... Закрийте Betaflight Configurator.");
        new Thread(()->{
            try{
                String result=WindowsSerial.detect();
                SwingUtilities.invokeLater(()->{
                    if(result.startsWith("FOUND|")){
                        String[] parts=result.split("\\|",3);
                        detectedPort=parts[1]; detectedVersion=parts.length>2?parts[2]:"Betaflight";
                        fCom.setText(detectedPort);
                        status.setText("FC: "+detectedPort+" · "+detectedVersion);
                        JOptionPane.showMessageDialog(this,"Знайдено FC на "+detectedPort+"\n"+detectedVersion+"\n\nПеред відправкою перевірте налаштування UART та VTX.");
                    }else{
                        status.setText("FC не знайдено");
                        JOptionPane.showMessageDialog(this,"Контролер не знайдено. Перевірте USB-кабель, драйвери та закрийте Betaflight Configurator.\n"+result);
                    }
                });
            }catch(Exception e){SwingUtilities.invokeLater(()->error(e));}
        },"fc-autodetect").start();
    }

    void backupFc(){
        if(detectedPort.isBlank()){
            JOptionPane.showMessageDialog(this,"Спочатку натисніть «ЗНАЙТИ FC (USB)».");return;
        }
        final String port=detectedPort;
        String stamp=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // Ask before opening the serial port. Native FileDialog stays readable on Windows.
        File chosen=chooseFile(this,true,"FC-"+port+"-"+stamp+".txt",null);
        if(chosen==null){status.setText("Резервне копіювання скасовано.");return;}
        final Path file=chosen.toPath().toAbsolutePath();
        if(Files.exists(file)){
            int answer=JOptionPane.showConfirmDialog(this,
                "Файл уже існує. Замінити його?\n"+file,
                "Підтвердження заміни",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
            if(answer!=JOptionPane.YES_OPTION)return;
        }
        status.setText("Читаємо резервну копію з "+port+". Не відключайте USB.");
        new Thread(()->{
            try{
                String dump=WindowsSerial.backup(port);
                if(!dump.contains("# dump") && !dump.contains("# version"))
                    throw new IOException("FC не повернув повний dump. Файл не створено.");
                // Write only after successful reading; keep existing files safe on failure.
                Path parent=file.getParent();
                if(parent!=null)Files.createDirectories(parent);
                Path temp=Files.createTempFile(parent,"vtx-backup-",".tmp");
                try{
                    Files.writeString(temp,dump,StandardCharsets.UTF_8);
                    Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temp);}
                SwingUtilities.invokeLater(()->{
                    status.setText("Backup FC: "+file);
                    JOptionPane.showMessageDialog(this,"Резервну копію збережено:\n"+file+"\n\nПеред записом конфігурації перевіримо UART і VTX.");
                });
            }catch(Exception e){SwingUtilities.invokeLater(()->error(e));}
        },"fc-backup").start();
    }

    // Read-only import. Never sends a command to the flight controller.
    void importFcDump(){
        File file=chooseFile(this,false,null,null);
        if(file==null)return;
        try{
            if(Files.size(file.toPath())>5_000_000)throw new IOException("Файл завеликий (максимум 5 МБ).");
            String dump=Files.readString(file.toPath(),StandardCharsets.UTF_8);
            String report=analyzeFcDump(dump,collect());
            importedDump=dump; importedDumpName=file.getName();
            JTextArea area=new JTextArea(report,26,76);
            area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
            area.setCaretPosition(0);
            // Keep this dialog readable under Windows high-contrast / native theme.
            area.setBackground(Color.WHITE);area.setForeground(Color.BLACK);
            JScrollPane scroll=new JScrollPane(area);
            scroll.setPreferredSize(new Dimension(790,540));
            Object[] options={"Закрити", "Зберегти звіт"};
            int result=JOptionPane.showOptionDialog(this,scroll,"Аналіз дампа FC — лише читання",
                JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,options,options[0]);
            if(result==1){
                File target=chooseFile(this,true,"fc-comparison.txt",null);
                if(target!=null){
                    if(target.exists()&&JOptionPane.showConfirmDialog(this,"Замінити файл?\n"+target,
                        "Підтвердження",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
                    Files.writeString(target.toPath(),report,StandardCharsets.UTF_8);
                    status.setText("Звіт збережено: "+target);
                }
            }
        }catch(Exception ex){error(ex);}
    }
    static String analyzeFcDump(String dump,Config c){
        if(!dump.matches("(?s).*#\\s*version.*")||!dump.contains("# serial"))
            throw new IllegalArgumentException("Це не схоже на повний CLI dump Betaflight: немає версії або розділу serial.");
        Map<String,String> settings=new LinkedHashMap<>();
        Map<Integer,Integer> ports=new TreeMap<>();
        List<String> table=new ArrayList<>(),vtxRules=new ArrayList<>();
        String version="невідома",board="невідома";
        for(String raw:dump.split("\\R")){
            String line=raw.trim();
            if(line.startsWith("# Betaflight /"))version=line.substring(2);
            if(line.startsWith("board_name "))board=line.substring(11).trim();
            if(line.startsWith("serial ")){
                String[] t=line.split("\\s+");
                if(t.length>=3)try{ports.put(Integer.parseInt(t[1]),Integer.parseInt(t[2]));}catch(NumberFormatException ignored){}
            }
            if(line.startsWith("set ")){
                int eq=line.indexOf('=');
                if(eq>4)settings.put(line.substring(4,eq).trim(),line.substring(eq+1).trim());
            }
            if(line.startsWith("vtxtable "))table.add(line);
            if(line.matches("vtx\\s+\\d+\\s+.*"))vtxRules.add(line);
        }
        if(ports.isEmpty())throw new IllegalArgumentException("У дампі немає налаштувань serial.");
        StringBuilder b=new StringBuilder();
        b.append("VtxConfig — аналіз дампа (ЛИШЕ ЧИТАННЯ)\n");
        b.append("========================================\n");
        b.append("Прошивка: ").append(version).append("\nПлата: ").append(board).append("\n");
        b.append("Поточний шаблон VtxConfig: ").append(c.template).append("\n");
        b.append("Вибраний порт у VtxConfig: ").append(c.uart).append("\n\nПОРТИ З ДАМПА\n");
        for(var entry:ports.entrySet()){
            int id=entry.getKey(),mask=entry.getValue();
            String port=id==20?"USB VCP":id>=0&&id<20?"UART"+(id+1):"serial "+id;
            List<String> roles=new ArrayList<>();
            if((mask&1)!=0)roles.add("MSP");
            if((mask&64)!=0)roles.add("Serial RX");
            if((mask&2048)!=0)roles.add("VTX SmartAudio");
            if((mask&8192)!=0)roles.add("VTX IRC Tramp");
            if(roles.isEmpty())roles.add(mask==0?"вільний":"інші функції: "+mask);
            b.append("  ").append(port).append(" : ").append(String.join(", ",roles)).append("\n");
        }
        int selectedId=-1;
        try{selectedId=Integer.parseInt(c.uart.replace("UART",""))-1;}catch(Exception ignored){}
        int selectedMask=ports.getOrDefault(selectedId,-1);
        boolean selectedVtx=(selectedMask&10240)!=0;
        b.append("\n========== ШВИДКЕ ПОРІВНЯННЯ ==========\n");
        b.append("Позначки: [OK] збігається  [!] відрізняється  [?] перевірити\n");
        String expectedRole=c.protocol.startsWith("IRC")?"IRC Tramp":"SmartAudio";
        int expectedFlag=c.protocol.startsWith("IRC")?8192:2048;
        if(selectedMask<0)b.append("[?] UART: ").append(c.uart).append(" не знайдено у дампі\n");
        else if((selectedMask&expectedFlag)!=0)b.append("[OK] UART: ").append(c.uart).append(" / ").append(expectedRole).append("\n");
        else b.append("[!] UART: ").append(c.uart).append(" у дампі: ")
            .append((selectedMask&10240)!=0?"інший VTX-протокол":selectedMask==0?"вільний":"інша функція (mask="+selectedMask+")")
            .append("; у програмі: ").append(expectedRole).append("\n");
        for(var entry:ports.entrySet())if(entry.getKey()!=selectedId&&(entry.getValue()&10240)!=0)
            b.append("[!] Інший VTX-порт: UART").append(entry.getKey()+1).append(" (перевірити перед зміною)\n");
        if(selectedMask>=0&&(selectedMask&65)!=0)b.append("[?] На вибраному UART є MSP або Serial RX: не змінювати автоматично\n");
        compareSetting(b,"Діапазон",settings.get("vtx_band"),String.valueOf(c.dband));
        compareSetting(b,"Канал",settings.get("vtx_channel"),String.valueOf(c.dchan));
        int requestedPower=c.powers[Math.max(0,Math.min(2,2))];
        compareSetting(b,"Потужність (індекс)",settings.get("vtx_power"),String.valueOf(requestedPower));
        int bands=-1,channels=-1,powerLevels=-1;
        for(String t:table){String[] parts=t.split("\\s+");try{
            if(parts.length>=3&&parts[1].equals("bands"))bands=Integer.parseInt(parts[2]);
            if(parts.length>=3&&parts[1].equals("channels"))channels=Integer.parseInt(parts[2]);
            if(parts.length>=3&&parts[1].equals("powerlevels"))powerLevels=Integer.parseInt(parts[2]);
        }catch(NumberFormatException ignored){}}
        b.append("[?] VTX-таблиця: дамп ").append(bands<0?"?":bands).append(" діапазонів x ")
            .append(channels<0?"?":channels).append(" каналів; ").append(powerLevels<0?"?":powerLevels)
            .append(" рівнів потужності; шаблон програми може мати інші значення\n");
        b.append("[?] AUX: у дампі ").append(vtxRules.size()).append(" правил; у програмі ")
            .append(c.steps.size()).append(". Перевірити відповідність каналів і діапазонів\n");
        b.append("[?] Модель VTX і фізичне підключення не визначаються з дампа\n");
        b.append("[БЕЗПЕКА] Звіт лише для читання; запис на FC заблоковано\n");
        b.append("=======================================\n");
        b.append("\nПОРІВНЯННЯ — ДЕТАЛІ\n");
        if(selectedMask<0)b.append("УВАГА: вибраний порт відсутній у дампі.\n");
        else if(!selectedVtx)b.append("УВАГА: ").append(c.uart).append(" не має функції VTX у цьому дампі.\n");
        else b.append(c.uart).append(" уже має функцію VTX у цьому дампі.\n");
        for(var entry:ports.entrySet())if(entry.getKey()!=selectedId&&(entry.getValue()&10240)!=0)
            b.append("УВАГА: VTX призначено також на UART").append(entry.getKey()+1).append(".\n");
        if(selectedMask>=0&&(selectedMask&64)!=0)b.append("НЕБЕЗПЕКА: вибраний порт зайнятий приймачем Serial RX.\n");
        if(selectedMask>=0&&(selectedMask&1)!=0)b.append("УВАГА: вибраний порт використовує MSP.\n");
        b.append("\nНАЛАШТУВАННЯ VTX\n");
        for(String key:List.of("vtx_band","vtx_channel","vtx_power","vtx_freq","vtx_halfduplex"))
            b.append("  ").append(key).append(" = ").append(settings.getOrDefault(key,"немає у дампі")).append("\n");
        b.append("  VTX-таблиця: ").append(table.size()).append(" рядків\n");
        for(String line:table)b.append("  ").append(line).append("\n");
        b.append("\nПРАВИЛА VTX / AUX: ").append(vtxRules.size()).append("\n");
        for(String line:vtxRules)b.append("  ").append(line).append("\n");
        b.append("\nВАЖЛИВО: дамп показує налаштування FC, але НЕ підтверджує\n");
        b.append("модель VTX, його живлення або фізичне підключення TX/RX.\n");
        b.append("ЖОДНИХ команд на FC не надіслано. Кнопка запису заблокована.\n");
        return b.toString();
    }

    static void compareSetting(StringBuilder b,String label,String actual,String desired){
        if(actual==null)b.append("[?] ").append(label).append(": у дампі немає значення; у програмі ").append(desired).append('\n');
        else b.append(actual.equals(desired)?"[OK] ":"[!] ").append(label)
            .append(": дамп ").append(actual).append(" / програма ").append(desired).append('\n');
    }

    // Read-only live comparison. Save the freshly read dump before showing any proposed changes.
    void compareLiveFc(){
        if(detectedPort.isBlank()){
            JOptionPane.showMessageDialog(this,"Спочатку натисніть ЗНАЙТИ FC (USB).", "Потрібен FC",JOptionPane.INFORMATION_MESSAGE);return;
        }
        final String port=detectedPort;
        final String oldDump=importedDump, oldName=importedDumpName;
        final Config desired=collect();
        String stamp=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File chosen=chooseFile(this,true,"FC-LIVE-"+port+"-"+stamp+".txt",null);
        if(chosen==null)return;
        Path target=chosen.toPath().toAbsolutePath();
        if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,"Замінити файл?\n"+target,
                "Підтвердження",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        status.setText("Зчитування поточного FC з "+port+". Не відключайте USB.");
        new Thread(()->{
            try{
                String live=WindowsSerial.backup(port);
                if(!live.matches("(?s).*#\\s*version.*") || !live.contains("# serial") || !live.matches("(?s).*\\bserial\\s+\\d+.*"))
                    throw new IOException("Неповний dump: немає версії або налаштувань UART. Файл не збережено.");
                Path parent=target.getParent();
                if(parent!=null)Files.createDirectories(parent);
                Path tmp=Files.createTempFile(parent,"vtx-live-",".tmp");
                try{Files.writeString(tmp,live,StandardCharsets.UTF_8);Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
                finally{Files.deleteIfExists(tmp);}
                String report=buildLiveComparison(live,oldDump,oldName,desired,port,target.toString());
                SwingUtilities.invokeLater(()->{
                    status.setText("Актуальний dump збережено: "+target);
                    JTextArea area=new JTextArea(report,27,80);
                    area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
                    area.setBackground(Color.WHITE);area.setForeground(Color.BLACK);area.setCaretPosition(0);
                    JScrollPane pane=new JScrollPane(area);pane.setPreferredSize(new Dimension(860,550));
                    Object[] opts={"Закрити","Зберегти звіт"};
                    int choice=JOptionPane.showOptionDialog(this,pane,"Актуальний FC — лише читання",
                        JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,opts,opts[0]);
                    if(choice==1){
                        File dest=chooseFile(this,true,"FC-live-comparison.txt",null);
                        if(dest!=null){try{
                            if(dest.exists()&&JOptionPane.showConfirmDialog(this,"Замінити звіт?", "Підтвердження",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
                            Files.writeString(dest.toPath(),report,StandardCharsets.UTF_8);
                        }catch(Exception ex){error(ex);}}
                    }
                });
            }catch(Exception ex){SwingUtilities.invokeLater(()->error(ex));}
        },"fc-live-compare").start();
    }
    static String dumpValue(String dump,String key){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^"+java.util.regex.Pattern.quote(key)+"\\s+(.+?)\\s*$").matcher(dump);
        return m.find()?m.group(1).trim():"невідомо";
    }
    static Map<Integer,String> serialLines(String dump){
        Map<Integer,String> result=new TreeMap<>();
        for(String raw:dump.split("\\R")){
            String line=raw.trim();
            if(line.startsWith("serial ")){
                String[] parts=line.split("\\s+");
                if(parts.length>=7)try{result.put(Integer.parseInt(parts[1]),line);}catch(NumberFormatException ignored){}
            }
        }
        return result;
    }
    static String buildLiveComparison(String live,String old,String oldName,Config config,String port,String path){
        StringBuilder b=new StringBuilder("VtxConfig 2.2.1 — АКТУАЛЬНИЙ FC (ЛИШЕ ЧИТАННЯ)\n\n");
        b.append("USB: ").append(port).append("\nСвіжий backup: ").append(path).append("\n");
        b.append("Плата: ").append(dumpValue(live,"board_name")).append("\n");
        b.append("Версія: ").append(live.lines().filter(line->line.startsWith("# Betaflight ")).findFirst().orElse("невідомо")).append("\n");
        b.append("Обрано у програмі: ").append(config.uart).append(" / ").append(config.protocol).append("\n\n");
        Map<Integer,String> now=serialLines(live);
        b.append("ПОТОЧНІ UART (зі свіжого dump):\n");
        for(var e:now.entrySet())b.append("serial ").append(e.getKey()).append(" [UART").append(e.getKey()+1).append("]: ").append(e.getValue()).append("\n");
        if(old!=null){
            b.append("\nПОРІВНЯННЯ З ІМПОРТОВАНИМ ДАМПОМ: ").append(oldName).append("\n");
            String oldBoard=dumpValue(old,"board_name"), liveBoard=dumpValue(live,"board_name");
            if(!oldBoard.equals("невідомо")&&!liveBoard.equals("невідомо")&&!oldBoard.equalsIgnoreCase(liveBoard))
                b.append("[СТОП] РІЗНІ ПЛАТИ! Імпорт: ").append(oldBoard).append(" / USB: ").append(liveBoard).append("\n");
            Map<Integer,String> before=serialLines(old);
            Set<Integer> ids=new TreeSet<>(now.keySet());ids.addAll(before.keySet());
            for(int id:ids){
                String a=before.get(id), z=now.get(id);
                if(Objects.equals(a,z))b.append("[OK] UART").append(id+1).append(": без змін\n");
                else b.append("[!] UART").append(id+1).append("\n    Імпорт: ").append(a==null?"відсутній":a).append("\n    Зараз:  ").append(z==null?"відсутній":z).append("\n");
            }
        }else b.append("\nІмпортований dump відсутній: порівняння з файлом пропущено.\n");
        b.append("\nПЕРЕГЛЯД ПРОПОНОВАНИХ ЗМІН\n");
        try{b.append(buildChangePreview(live,config,"АКТУАЛЬНИЙ FC"));}
        catch(Exception e){b.append("Неможливо побудувати попередній перегляд: ").append(e.getMessage());}
        b.append("\n\nНІЧОГО НЕ ВІДПРАВЛЕНО. ЗАПИС ЗАБЛОКОВАНО.\n");
        return b.toString();
    }

    // A preview is intentionally read-only: never opens COM ports or sends CLI commands.
    void previewFcChanges(){
        if(importedDump==null){
            JOptionPane.showMessageDialog(this,"Спочатку натисніть ІМПОРТ ДАМПА та виберіть повний dump саме цього FC.",
                "Потрібен дамп",JOptionPane.INFORMATION_MESSAGE);return;
        }
        try{
            String report=buildChangePreview(importedDump,collect(),importedDumpName);
            JTextArea area=new JTextArea(report,27,78);
            area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
            area.setBackground(Color.WHITE);area.setForeground(Color.BLACK);area.setCaretPosition(0);
            JScrollPane pane=new JScrollPane(area);pane.setPreferredSize(new Dimension(840,550));
            Object[] options={"Закрити", "Зберегти попередній перегляд"};
            int answer=JOptionPane.showOptionDialog(this,pane,"Попередній перегляд — запис вимкнено",
                JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,options,options[0]);
            if(answer==1){
                File target=chooseFile(this,true,"vtx-preview.txt",null);
                if(target!=null){
                    if(target.exists()&&JOptionPane.showConfirmDialog(this,"Замінити файл?\n"+target,
                        "Підтвердження",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
                    Files.writeString(target.toPath(),report,StandardCharsets.UTF_8);
                    status.setText("Попередній перегляд: "+target);
                }
            }
        }catch(Exception e){error(e);}
    }
    static String buildChangePreview(String dump,Config c,String filename){
        if(!dump.matches("(?s).*#\\s*version.*")||!dump.contains("# serial"))
            throw new IllegalArgumentException("Потрібен повний Betaflight dump із розділом serial.");
        Map<Integer,String[]> serial=new TreeMap<>();
        for(String raw:dump.split("\\R")){
            String line=raw.trim();
            if(line.startsWith("serial ")){
                String[] tokens=line.split("\\s+");
                if(tokens.length>=7)try{serial.put(Integer.parseInt(tokens[1]),tokens);}catch(NumberFormatException ignored){}
            }
        }
        if(serial.isEmpty())throw new IllegalArgumentException("Не знайдено повних serial-команд у дампі.");
        int uart=Integer.parseInt(c.uart.substring(4))-1;
        int desiredFlag=c.protocol.startsWith("IRC")?8192:2048;
        String desiredName=c.protocol.startsWith("IRC")?"IRC Tramp":"SmartAudio";
        StringBuilder b=new StringBuilder();
        b.append("VtxConfig 2.2.0 — ПЕРЕГЛЯД ЗМІН (ЛИШЕ ЧИТАННЯ)\\n".replace("\\n","\n"));
        b.append("Дамп: ").append(filename).append("\nОбрано: ").append(c.uart).append(" / ").append(desiredName).append("\n\n");
        b.append("ПЕРЕВІРКА UART\n");
        String[] selected=serial.get(uart);
        boolean blocked=false;
        if(selected==null){b.append("[СТОП] Обраний UART відсутній у дампі.\n");blocked=true;}
        else{
            int mask=Integer.parseInt(selected[2]);
            b.append("Поточна команда: ").append(String.join(" ",selected)).append("\n");
            if((mask&65)!=0){b.append("[СТОП] UART зайнятий MSP або Serial RX.\n");blocked=true;}
            if((mask&10240)!=0&&(mask&desiredFlag)==0)b.append("[!] На UART призначено інший VTX-протокол.\n");
            if((mask&desiredFlag)!=0)b.append("[OK] Потрібна VTX-функція вже призначена.\n");
        }
        List<Integer> oldVtx=new ArrayList<>();
        for(var entry:serial.entrySet())if(entry.getKey()!=uart){
            int mask=Integer.parseInt(entry.getValue()[2]);
            if((mask&10240)!=0){
                oldVtx.add(entry.getKey());
                b.append("[!] Інша VTX-функція на UART").append(entry.getKey()+1)
                    .append(". Не змінювати без підтвердження фізичного підключення.\n");
            }
        }
        b.append("\nМОЖЛИВІ КОМАНДИ ДЛЯ РУЧНОЇ ПЕРЕВІРКИ (НЕ ВІДПРАВЛЯЮТЬСЯ)\n");
        if(blocked)b.append("[СТОП] Генерацію serial-команд заблоковано.\n");
        else{
            for(int id:oldVtx){
                String[] tokens=serial.get(id).clone();
                int mask=Integer.parseInt(tokens[2]);
                tokens[2]=String.valueOf(mask&~10240);
                b.append("Було: ").append(String.join(" ",serial.get(id))).append("\n");
                b.append("Може бути: ").append(String.join(" ",tokens)).append("\n");
            }
            String[] tokens=selected.clone();
            int mask=Integer.parseInt(tokens[2]);
            tokens[2]=String.valueOf((mask&~10240)|desiredFlag);
            b.append("Було: ").append(String.join(" ",selected)).append("\n");
            b.append("Може бути: ").append(String.join(" ",tokens)).append("\n");
        }
        b.append("\nДОДАТКОВІ ПЕРЕВІРКИ\n");
        b.append("[?] Підтвердити фізичний TX-пін і модель VTX.\n");
        b.append("[?] Перевірити таблицю частот, рівні потужності та AUX у звіті імпорту.\n");
        b.append("[?] Перед будь-яким записом зробити свіжий BACKUP саме цього FC.\n");
        b.append("[?] Без акумулятора VTX може бути вимкнений: dump не підтверджує його роботу.\n");
        b.append("\nБЕЗПЕКА: НІЧОГО НЕ ВІДПРАВЛЕНО. КНОПКА ЗАПИСУ ЗАБЛОКОВАНА.\n");
        return b.toString();
    }

    void sendToFc(){
        JOptionPane.showMessageDialog(this,
            "Відправка поки заблокована. Спочатку створіть BACKUP FC і перевірте\n"+
            "поточний UART та конфігурацію VTX. Не надсилаємо неперевірені команди.",
            "Безпека FC",JOptionPane.WARNING_MESSAGE);
    }

    static final class WindowsSerial{
        static String detect() throws Exception {
            String script="""
$ErrorActionPreference='Stop'
$ports=[System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
if (-not $ports) { Write-Output 'NOT_FOUND|Немає COM-портів'; exit 0 }
foreach($name in $ports) {
  $sp=$null
  try {
    $sp=[System.IO.Ports.SerialPort]::new($name,115200,'None',8,'One')
    $sp.ReadTimeout=350; $sp.WriteTimeout=800
    $sp.DtrEnable=$false; $sp.RtsEnable=$false
    $sp.Open(); Start-Sleep -Milliseconds 200
    $sp.DiscardInBuffer(); $sp.Write("#`r`n"); Start-Sleep -Milliseconds 250
    $sp.Write("version`r`n")
    $reply=''; $until=(Get-Date).AddSeconds(2)
    while((Get-Date) -lt $until) {
      Start-Sleep -Milliseconds 100
      $reply += $sp.ReadExisting()
      if($reply -match '(?i)Betaflight.*(\\d+\\.\\d+\\.\\d+)') { break }
    }
    if($reply -match '(?im)(Betaflight[^\r\n]*)') {
      $version=$Matches[1].Trim()
      try { $sp.Write("exit`r`n") } catch {}
      Write-Output ('FOUND|'+$name+'|'+$version); exit 0
    }
    try { $sp.Write("exit`r`n") } catch {}
  } catch {} finally { if($null -ne $sp){try{$sp.Close();$sp.Dispose()}catch{}} }
}
Write-Output 'NOT_FOUND|COM-порти є, але Betaflight не відповів. Можливо, порт зайнятий.'
""";
            String encoded=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",encoded).redirectErrorStream(true).start();
            boolean finished=process.waitFor(45,TimeUnit.SECONDS);
            if(!finished){process.destroyForcibly();throw new IOException("Час пошуку FC вичерпано");}
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            for(String line:output.split("\\R"))if(line.startsWith("FOUND|")||line.startsWith("NOT_FOUND|"))return line;
            return "NOT_FOUND|"+output;
        }
        static String backup(String port) throws Exception {
            if(!port.toUpperCase(Locale.ROOT).matches("COM\\d+"))throw new IOException("Некоректний COM-порт");
            String script="""
param([string]$portName)
$ErrorActionPreference='Stop'
$sp=[System.IO.Ports.SerialPort]::new($portName,115200,'None',8,'One')
$sp.ReadTimeout=300; $sp.WriteTimeout=1500
$sp.DtrEnable=$false; $sp.RtsEnable=$false
try {
  $sp.Open(); Start-Sleep -Milliseconds 300
  $sp.DiscardInBuffer(); $sp.Write("#`r`n"); Start-Sleep -Milliseconds 350
  $null=$sp.ReadExisting()
  $sp.Write("dump`r`n")
  $data=''; $last=(Get-Date); $end=(Get-Date).AddSeconds(55)
  while((Get-Date) -lt $end) {
    Start-Sleep -Milliseconds 120
    $part=$sp.ReadExisting()
    if($part.Length -gt 0){ $data+=$part; $last=Get-Date }
    if($data.Length -gt 100 -and ((Get-Date)-$last).TotalSeconds -gt 2.5){break}
  }
  if($data.Length -lt 100 -or $data -notmatch '(?im)^# (dump|version)') { throw 'Incomplete dump' }
  [Console]::OutputEncoding=[Text.Encoding]::UTF8
  [Console]::Write($data)
} finally { if($sp.IsOpen){try{$sp.Write("exit`r`n")}catch{}; $sp.Close()};$sp.Dispose() }
""";
            Path tmp=Files.createTempFile("vtx-fc-backup-",".ps1");
            try{
                Files.writeString(tmp,script,StandardCharsets.UTF_8);
                Path output=Files.createTempFile("vtx-fc-backup-output-",".txt");
                try {
                Process process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",tmp.toString(),port)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if(!process.waitFor(80,TimeUnit.SECONDS)){
                    process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new IOException("Тайм-аут читання FC (80 с). Перевірте USB та спробуйте знову.");
                }
                String result=Files.readString(output,StandardCharsets.UTF_8);
                if(process.exitValue()!=0)throw new IOException("Backup не вдався: "+result);
                return result;
                } finally { Files.deleteIfExists(output); }
            }finally{Files.deleteIfExists(tmp);}
        }
        static String send(String port,String data,long timeoutMs)throws Exception{String p=port.toUpperCase(Locale.ROOT);if(!p.matches("COM\\d+"))throw new IOException("Некоректний COM-порт: "+port);Process mode=new ProcessBuilder("cmd","/c","mode",p+":","BAUD=115200","PARITY=N","DATA=8","STOP=1").redirectErrorStream(true).start();mode.waitFor(3,TimeUnit.SECONDS);try(FileInputStream in=new FileInputStream("\\\\.\\"+p);FileOutputStream out=new FileOutputStream("\\\\.\\"+p)){out.write(data.getBytes(StandardCharsets.US_ASCII));out.flush();long end=System.currentTimeMillis()+timeoutMs;ByteArrayOutputStream buf=new ByteArrayOutputStream();byte[] b=new byte[1024];while(System.currentTimeMillis()<end){while(in.available()>0){int n=in.read(b);if(n>0)buf.write(b,0,n);}if(buf.size()>0&&new String(buf.toByteArray(),StandardCharsets.US_ASCII).contains("#"))break;Thread.sleep(20);}return buf.toString(StandardCharsets.US_ASCII);}}
    }

    static Path resolveDataFile(){String os=System.getProperty("os.name").toLowerCase();Path base;if(os.contains("win")){String a=System.getenv("APPDATA");base=a!=null?Path.of(a):Path.of(System.getProperty("user.home"));}else if(os.contains("mac"))base=Path.of(System.getProperty("user.home"),"Library","Application Support");else{String x=System.getenv("XDG_CONFIG_HOME");base=x!=null?Path.of(x):Path.of(System.getProperty("user.home"),".config");}Path d=base.resolve(APP_NAME);try{Files.createDirectories(d);}catch(IOException ignored){}return d.resolve("vtx_configs.json");}
    void load(){dataFile=resolveDataFile();if(!Files.exists(dataFile))return;try{configs.addAll(Json.toConfigs(Files.readString(dataFile,StandardCharsets.UTF_8)));status.setText("Завантажено конфігурацій: "+configs.size());}catch(Exception e){status.setText("Колекцію не прочитано: "+e.getMessage());}}
    void persist(){try{Files.writeString(dataFile,Json.stringify(configs),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);}catch(IOException e){status.setText("Не збережено: "+e.getMessage());}}
    // Set Swing defaults BEFORE constructing controls, including popup menus and dialogs.
    static void installReadableDarkDefaults(){
        Color bg=new Color(22,31,46), field=new Color(35,48,67);
        Color fg=new Color(242,246,252), selected=new Color(53,113,177);
        String[] foreground={"Label.foreground","CheckBox.foreground","RadioButton.foreground",
            "ComboBox.foreground","ComboBox.selectionForeground","List.foreground","List.selectionForeground",
            "TextField.foreground","TextArea.foreground","Spinner.foreground","FormattedTextField.foreground",
            "OptionPane.messageForeground","TitledBorder.titleColor","Menu.foreground","MenuItem.foreground"};
        for(String key:foreground)UIManager.put(key,new javax.swing.plaf.ColorUIResource(fg));
        String[] backgrounds={"Panel.background","Viewport.background","OptionPane.background",
            "CheckBox.background","RadioButton.background","ScrollPane.background"};
        for(String key:backgrounds)UIManager.put(key,new javax.swing.plaf.ColorUIResource(bg));
        for(String key:new String[]{"ComboBox.background","ComboBox.selectionBackground",
                "List.background","TextField.background","TextArea.background",
                "Spinner.background","FormattedTextField.background"})
            UIManager.put(key,new javax.swing.plaf.ColorUIResource(key.contains("selection")?selected:field));
        UIManager.put("List.selectionBackground",new javax.swing.plaf.ColorUIResource(selected));
        UIManager.put("TextField.caretForeground",new javax.swing.plaf.ColorUIResource(fg));
        UIManager.put("TextArea.caretForeground",new javax.swing.plaf.ColorUIResource(fg));
    }
    void setDarkTheme(boolean dark){
        Color bg=dark?new Color(22,31,46):UIManager.getColor("Panel.background");
        Color fg=dark?new Color(242,246,252):UIManager.getColor("Label.foreground");
        for(Window w:Window.getWindows())applyColors(w,bg,fg,dark);
    }
    void applyColors(Component c,Color bg,Color fg,boolean dark){
        Color field=dark?new Color(35,48,67):Color.WHITE;
        if(c instanceof JPanel||c instanceof JScrollPane||c instanceof JViewport)c.setBackground(bg);
        if(c instanceof JLabel||c instanceof JCheckBox)c.setForeground(fg);
        if(c instanceof JCheckBox cb){cb.setOpaque(false);cb.setBackground(bg);}
        if(c instanceof JTextField||c instanceof JTextArea||c instanceof JList||c instanceof JComboBox||c instanceof JSpinner){
            c.setBackground(field);c.setForeground(fg);
        }
        if(c instanceof JComboBox<?> combo){
            // Replace the Windows native combo UI: its closed-cell painter can ignore
            // both foreground and the custom renderer, producing white-on-white text.
            combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
            combo.setOpaque(true);
            combo.setBackground(field);
            combo.setForeground(fg);
            combo.setRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    super.getListCellRendererComponent(list,value,index,selected,focus);
                    setOpaque(true);
                    setBackground(selected?new Color(53,113,177):field);
                    setForeground(Color.WHITE);
                    return this;
                }
            });
        }
        if(c instanceof JSpinner spinner && spinner.getEditor() instanceof JSpinner.DefaultEditor editor){
            editor.getTextField().setBackground(field);
            editor.getTextField().setForeground(fg);
            editor.getTextField().setCaretColor(fg);
        }
        if(c instanceof Container co)for(Component x:co.getComponents())applyColors(x,bg,fg,dark);
        c.repaint();
    }

    static final class Json{
        static String escape(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray()){switch(c){case '\\'->b.append("\\\\");case '"'->b.append("\\\"");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->b.append(c);}}return b.toString();}
        static String stringify(List<Config> list){StringBuilder b=new StringBuilder("{\n  \"formatVersion\": ").append(JSON_VERSION).append(",\n  \"configs\": [\n");for(int i=0;i<list.size();i++){Config c=list.get(i);b.append("    {\n");field(b,"name",c.name,true);field(b,"selectedVtx",c.selectedVtx,true);field(b,"template",c.template,true);field(b,"uart",c.uart,true);field(b,"protocol",c.protocol,true);field(b,"aux",c.aux,true);num(b,"dband",c.dband,true);num(b,"dchan",c.dchan,true);b.append("      \"powers\": [").append(c.powers[0]).append(", ").append(c.powers[1]).append(", ").append(c.powers[2]).append("],\n");bool(b,"incTable",c.incTable,true);bool(b,"includePortSetup",c.includePortSetup,true);b.append("      \"steps\": [\n");for(int j=0;j<c.steps.size();j++){BandStep s=c.steps.get(j);b.append("        [\"").append(escape(s.aux())).append("\", ").append(s.band()).append(", ").append(s.channel()).append(", ").append(s.start()).append(", ").append(s.end()).append("]").append(j+1<c.steps.size()?",":"").append('\n');}b.append("      ]\n    }").append(i+1<list.size()?",":"").append('\n');}return b.append("  ]\n}\n").toString();}
        static void field(StringBuilder b,String k,String v,boolean comma){b.append("      \"").append(k).append("\": \"").append(escape(v)).append("\"").append(comma?",":"").append('\n');}static void num(StringBuilder b,String k,int v,boolean comma){b.append("      \"").append(k).append("\": ").append(v).append(comma?",":"").append('\n');}static void bool(StringBuilder b,String k,boolean v,boolean comma){b.append("      \"").append(k).append("\": ").append(v).append(comma?",":"").append('\n');}
        static List<Config> toConfigs(String raw){Object root=new Parser(raw).parse();if(!(root instanceof Map<?,?> m))throw new IllegalArgumentException("JSON root must be object");Object arr=m.get("configs");if(!(arr instanceof List<?> l))throw new IllegalArgumentException("JSON field 'configs' must be array");List<Config> out=new ArrayList<>();for(Object o:l){if(!(o instanceof Map<?,?> m2))throw new IllegalArgumentException("config must be object");Config c=new Config();c.name=str(m2,"name","Без назви");c.selectedVtx=str(m2,"selectedVtx","");c.template=str(m2,"template","");c.uart=str(m2,"uart","UART1");c.protocol=str(m2,"protocol","TBS SmartAudio 2.0");c.aux=str(m2,"aux","AUX3");c.dband=num(m2,"dband",5);c.dchan=num(m2,"dchan",1);Object ps=m2.get("powers");if(ps instanceof List<?> p){for(int i=0;i<3&&i<p.size();i++)c.powers[i]=toInt(p.get(i),c.powers[i]);}c.incTable=bool(m2,"incTable",true);c.includePortSetup=bool(m2,"includePortSetup",false);Object ss=m2.get("steps");if(ss instanceof List<?> sl){List<BandStep> steps=new ArrayList<>();for(Object so:sl){if(so instanceof List<?> x&&x.size()==5)steps.add(new BandStep(String.valueOf(x.get(0)),toInt(x.get(1),0),toInt(x.get(2),1),toInt(x.get(3),900),toInt(x.get(4),1000)));}if(steps.size()==6)c.steps=steps;}out.add(c);}return out;}
        static String str(Map<?,?>m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}static int num(Map<?,?>m,String k,int d){return toInt(m.get(k),d);}static boolean bool(Map<?,?>m,String k,boolean d){Object v=m.get(k);return v instanceof Boolean x?x:d;}static int toInt(Object v,int d){return v instanceof Number n?n.intValue():d;}
        static final class Parser{final String s;int p=0;Parser(String s){this.s=s;}void ws(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}Object parse(){ws();Object v=value();ws();if(p!=s.length())err("Trailing data");return v;}Object value(){ws();if(p>=s.length())err("Unexpected end");char c=s.charAt(p);if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(s.startsWith("true",p)){p+=4;return true;}if(s.startsWith("false",p)){p+=5;return false;}if(s.startsWith("null",p)){p+=4;return null;}if(c=='-'||Character.isDigit(c))return number();err("Unexpected token");return null;}
            Map<String,Object> object(){Map<String,Object> m=new LinkedHashMap<>();p++;ws();if(peek('}')){p++;return m;}while(true){ws();if(!peek('"'))err("Object key must be string");String k=string();ws();expect(':');m.put(k,value());ws();if(peek('}')){p++;return m;}expect(',');}}
            List<Object> array(){List<Object> a=new ArrayList<>();p++;ws();if(peek(']')){p++;return a;}while(true){a.add(value());ws();if(peek(']')){p++;return a;}expect(',');}}
            String string(){expect('"');StringBuilder b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c=='\\'){if(p>=s.length())err("Bad escape");char e=s.charAt(p++);switch(e){case '"'->b.append('"');case '\\'->b.append('\\');case '/'->b.append('/');case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(p+4>s.length())err("Bad unicode escape");int cp=Integer.parseInt(s.substring(p,p+4),16);p+=4;b.append((char)cp);}default->err("Bad escape");}}else{if(c<0x20)err("Control character in string");b.append(c);}}err("Unterminated string");return null;}
            Number number(){int st=p;if(s.charAt(p)=='-')p++;if(p>=s.length()||!Character.isDigit(s.charAt(p)))err("Bad number");if(s.charAt(p)=='0')p++;else while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;if(p<s.length()&&s.charAt(p)=='.'){p++;if(p>=s.length()||!Character.isDigit(s.charAt(p)))err("Bad number");while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;}if(p<s.length()&&(s.charAt(p)=='e'||s.charAt(p)=='E')){p++;if(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-'))p++;if(p>=s.length()||!Character.isDigit(s.charAt(p)))err("Bad exponent");while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;}String n=s.substring(st,p);try{return n.contains(".")||n.contains("e")||n.contains("E")?Double.parseDouble(n):Long.parseLong(n);}catch(Exception e){err("Bad number");return 0;}}
            boolean peek(char c){return p<s.length()&&s.charAt(p)==c;}void expect(char c){ws();if(!peek(c))err("Expected '"+c+"'");p++;}void err(String m){throw new IllegalArgumentException(m+" at character "+p);}
        }
    }

    public static void main(String[] args){try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}installReadableDarkDefaults();SwingUtilities.invokeLater(()->{VtxApp app=new VtxApp();app.setDarkTheme(true);app.setVisible(true);});}
}
