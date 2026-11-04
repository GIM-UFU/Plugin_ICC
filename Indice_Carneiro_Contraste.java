import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Indice Carneiro de Contraste (ICC) / Carneiro Contrast Index (CCI).
 *
 * <h2>Referencia</h2>
 * Implementacao do Capitulo 6 de:
 * <pre>
 *   CARNEIRO, P. C. Proposta de metodologia para realce de contraste em
 *   imagens de mamas densas (tese de doutorado).
 * </pre>
 *
 * <h2>Algoritmo (Eq. 26)</h2>
 * <ol>
 *   <li><b>Normalizacao.</b> A imagem e levada para [0,1] dividindo cada pixel
 *       pelo maximo do fundo de escala ({@code 2^bits - 1}; ex.: 4095 para
 *       12 bits, 1023 para 10 bits). O mesmo divisor e usado para todas as
 *       fatias processadas. No modo "Automatico" usa-se o Bits Stored do
 *       cabecalho DICOM quando ele existir.</li>
 *   <li><b>Mapa de desvios padroes.</b> Uma janela deslizante 3x3 percorre
 *       todos os pixels e calcula o desvio padrao local <i>amostral</i>
 *       (divisor n-1 = 8, igual ao {@code stdfilt} do MATLAB), com padding
 *       simetrico (espelhado) nas bordas. O resultado e uma imagem do mesmo
 *       tamanho da original: o "mapa" de desvios padroes.</li>
 *   <li><b>Indice.</b> {@code ICC = media(valores do mapa > 0) x 1000}.
 *       Pixels com desvio exatamente zero (regioes uniformes, como o fundo
 *       preto da mamografia) sao excluidos da media.</li>
 * </ol>
 *
 * <h2>Uso</h2>
 * <ul>
 *   <li><b>Instalacao:</b> copie este arquivo para {@code ImageJ/plugins/} e
 *       use <i>Plugins &gt; Compile and Run...</i> (ou reinicie o ImageJ apos
 *       compilar). O plugin aparece como "Indice Carneiro Contraste".</li>
 *   <li><b>Entrada:</b> a imagem ativa (8, 16 ou 32 bits, RGB ou 8 bits com
 *       LUT; imagens coloridas sao convertidas para tons de cinza com os
 *       pesos do {@code im2gray} do MATLAB). Opcionalmente, uma ROI de
 *       area restringe a media aos pixels dentro dela (o mapa continua sendo
 *       calculado na imagem inteira).</li>
 *   <li><b>Saida:</b> uma linha por fatia na tabela "ICC" (imagem, fatia,
 *       fundo de escala, origem da escala, numel e ICC) e, opcionalmente, a
 *       pilha "Mapa_DP_&lt;titulo&gt;" com os mapas de desvio padrao (32 bits,
 *       ja na escala normalizada, sem a constante).</li>
 *   <li><b>Macro:</b> o plugin e gravavel pelo Recorder. As chaves sao a
 *       primeira palavra de cada campo do dialogo, por exemplo:
 *       <pre>
 * run("Indice Carneiro Contraste",
 *     "normalizacao=[12 bits (4095)] constante=1000 mostrar calcular media");
 *       </pre>
 *       ({@code calcular} so existe em pilhas e {@code media} so com ROI de
 *       area; omita uma caixa de selecao para desmarca-la).</li>
 * </ul>
 */
public class Indice_Carneiro_Contraste implements PlugIn {

    /**
     * Opcoes de normalizacao exibidas no dialogo. O texto e significativo:
     * {@link #normalizationMax} identifica a opcao pelo prefixo da string,
     * entao altere os dois lugares juntos.
     */
    private static final String[] NORM_OPTIONS = {
        "Automatico", "8 bits (255)", "10 bits (1023)", "12 bits (4095)",
        "14 bits (16383)", "16 bits (65535)", "Maximo da imagem", "Nenhuma (ja em 0-1)"
    };

    // Ultimas escolhas do usuario. Sao estaticas para que o dialogo reabra com
    // os valores usados na chamada anterior (durante a mesma sessao do ImageJ).

    /** Opcao de normalizacao selecionada (um dos itens de {@link #NORM_OPTIONS}). */
    private static String normChoice = NORM_OPTIONS[0];
    /** Fator que multiplica a media dos desvios (1000 na Eq. 26). */
    private static double constant = 1000.0;
    /** Se verdadeiro, exibe a pilha com os mapas de desvio padrao. */
    private static boolean showMap = true;
    /** Se verdadeiro (e a imagem for uma pilha), processa todas as fatias. */
    private static boolean allSlices = false;
    /** Se verdadeiro (e houver ROI de area), a media considera so os pixels da ROI. */
    private static boolean useRoi = false;

    /**
     * Ponto de entrada chamado pelo ImageJ: mostra o dialogo, calcula o ICC de
     * cada fatia selecionada e publica os resultados.
     *
     * @param arg argumento do menu (nao utilizado)
     */
    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();   // exibe erro e retorna null se nao houver imagem
        if (imp == null) return;

        int nSlices = imp.getStackSize();
        Roi roi = imp.getRoi();
        boolean hasAreaRoi = roi != null && roi.isArea();   // linhas/pontos nao contam

        // ---------------------------------------------------------------
        // 1. Dialogo de parametros. As caixas de "todas as fatias" e "ROI"
        //    so aparecem quando fazem sentido; a leitura abaixo precisa
        //    seguir exatamente a mesma ordem e as mesmas condicoes.
        // ---------------------------------------------------------------
        GenericDialog gd = new GenericDialog("Indice Carneiro de Contraste (ICC)");
        gd.addChoice("Normalizacao (fundo de escala):", NORM_OPTIONS, normChoice);
        gd.addNumericField("Constante multiplicadora:", constant, 1);
        gd.addCheckbox("Mostrar mapa de desvios padroes", showMap);
        if (nSlices > 1)
            gd.addCheckbox("Calcular todas as fatias (" + nSlices + ")", allSlices);
        if (hasAreaRoi)
            gd.addCheckbox("Media apenas dentro da ROI", useRoi);
        gd.addMessage("Desvio padrao local 3x3 (n-1), padding simetrico;\n"
                + "ICC = media dos desvios > 0 x constante (Eq. 26).");
        gd.showDialog();
        if (gd.wasCanceled()) return;

        normChoice = gd.getNextChoice();
        constant = gd.getNextNumber();
        showMap = gd.getNextBoolean();
        // O curto-circuito do && garante que getNextBoolean() so e chamado
        // quando a caixa correspondente foi de fato adicionada ao dialogo.
        boolean doAll = nSlices > 1 && (allSlices = gd.getNextBoolean());
        boolean doRoi = hasAreaRoi && (useRoi = gd.getNextBoolean());

        if (imp.getType() == ImagePlus.COLOR_RGB || imp.getType() == ImagePlus.COLOR_256)
            IJ.log("ICC: imagem colorida convertida para tons de cinza (pesos do im2gray do MATLAB).");

        // ---------------------------------------------------------------
        // 2. Preparacao: ROI, intervalo de fatias e estruturas de saida.
        // ---------------------------------------------------------------
        int w = imp.getWidth(), h = imp.getHeight();
        ImageProcessor mask = null;      // mascara da ROI (coordenadas relativas a rb)
        java.awt.Rectangle rb = null;    // retangulo envolvente da ROI
        if (doRoi) {
            mask = roi.getMask();       // null para ROI retangular
            rb = roi.getBounds();
        }

        int first = doAll ? 1 : imp.getCurrentSlice();
        int last = doAll ? nSlices : imp.getCurrentSlice();
        ImageStack mapStack = showMap ? new ImageStack(w, h) : null;
        // Reaproveita a tabela "ICC" se ja estiver aberta, acumulando medicoes.
        ResultsTable rt = ResultsTable.getResultsTable("ICC");
        if (rt == null) rt = new ResultsTable();

        ImageStack stack = imp.getStack();

        // ---------------------------------------------------------------
        // 3. Fundo de escala.
        //    Fundo de escala UNICO para todas as fatias processadas (Cap. 6:
        //    dividir pelo maximo do fundo da escala). Calcular por fatia
        //    deixaria fatias da mesma pilha com divisores diferentes e ICCs
        //    nao comparaveis.
        // ---------------------------------------------------------------
        double stackMax = Double.NEGATIVE_INFINITY;
        for (int s = first; s <= last; s++) {
            double m = stack.getProcessor(s).getStats().max;
            if (m > stackMax) stackMax = m;
        }
        String[] origin = new String[1];   // "parametro de saida" com a descricao da escala
        double scale = normalizationMax(normChoice, imp, stackMax, origin);
        if (!(scale > 0)) {   // tambem captura NaN
            IJ.error("ICC", "Fundo de escala invalido (" + scale + ").");
            return;
        }
        if (stackMax > scale)
            IJ.log("ICC: AVISO - '" + imp.getTitle() + "' tem pixel maximo " + stackMax
                    + " acima do fundo de escala " + scale
                    + "; a imagem normalizada passa de 1. Confira a normalizacao.");

        // ---------------------------------------------------------------
        // 4. Calculo por fatia: mapa de desvios -> media dos valores > 0.
        // ---------------------------------------------------------------
        for (int s = first; s <= last; s++) {
            IJ.showProgress(s - first, last - first + 1);
            ImageProcessor ip = stack.getProcessor(s);
            // 8 bits com LUT de cor: aplica a LUT antes, para que a conversao
            // para cinza use as cores exibidas e nao os indices da paleta.
            if (imp.getType() == ImagePlus.COLOR_256) ip = ip.convertToRGB();
            float[] px = toFloatPixels(ip);

            // A normalizacao (multiplicar por 1/scale) e feita dentro do
            // calculo do mapa, sem criar uma copia normalizada da imagem.
            float[] map = stdMap3x3(px, w, h, 1.0 / scale);

            // Media dos desvios padroes > 0 (opcionalmente so dentro da ROI)
            double sum = 0;
            long numel = 0;   // quantidade de pixels com desvio > 0
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (doRoi && !insideRoi(x, y, rb, mask)) continue;
                    float v = map[y * w + x];
                    if (v > 0) { sum += v; numel++; }
                }
            }
            // Imagem/ROI totalmente uniforme: sem pixels validos, ICC = 0.
            double icc = numel > 0 ? (sum / numel) * constant : 0.0;

            rt.incrementCounter();
            rt.addValue("Imagem", imp.getTitle());
            rt.addValue("Fatia", s);
            rt.addValue("Fundo de escala", scale);
            rt.addValue("Origem da escala", origin[0]);
            rt.addValue("numel (dp>0)", numel);
            rt.addValue("ICC", icc);

            if (mapStack != null)
                mapStack.addSlice(stack.getShortSliceLabel(s), new FloatProcessor(w, h, map));
        }
        IJ.showProgress(1.0);

        // ---------------------------------------------------------------
        // 5. Saida: tabela de resultados e (opcional) pilha de mapas.
        // ---------------------------------------------------------------
        rt.show("ICC");

        if (mapStack != null) {
            ImagePlus mapImp = new ImagePlus("Mapa_DP_" + imp.getShortTitle(), mapStack);
            mapImp.setCalibration(imp.getCalibration());   // mantem tamanho de pixel
            mapImp.resetDisplayRange();
            mapImp.show();
        }
    }

    /**
     * Converte qualquer tipo de processador (8/16/32 bits, RGB) para um array
     * float com os valores originais, sem reescalar. Para RGB, o valor e o
     * cinza do {@code im2gray}/{@code rgb2gray} do MATLAB
     * (0.298936 R + 0.587043 G + 0.114021 B, arredondado para 8 bits).
     * A conversao padrao do ImageJ ((R+G+B)/3) daria outro ICC.
     *
     * @param ip processador da fatia
     * @return pixels em ordem de linhas ({@code indice = y * largura + x})
     */
    private static float[] toFloatPixels(ImageProcessor ip) {
        if (ip instanceof ColorProcessor) {
            int[] rgb = (int[]) ip.getPixels();
            float[] out = new float[rgb.length];
            for (int i = 0; i < rgb.length; i++) {
                int r = (rgb[i] >> 16) & 0xff, g = (rgb[i] >> 8) & 0xff, b = rgb[i] & 0xff;
                out[i] = (float) Math.round(0.298936021293776 * r + 0.587043074451121 * g
                        + 0.114020904255103 * b);
            }
            return out;
        }
        return (float[]) ip.convertToFloatProcessor().getPixels();
    }

    /**
     * Determina o divisor que leva a imagem para [0,1], conforme a opcao escolhida.
     *
     * <p>Regras do modo "Automatico", em ordem de prioridade:
     * <ol>
     *   <li>imagem de 16 bits com Bits Stored (0028,0101) no DICOM:
     *       {@code 2^bits - 1} (fundo de escala real do detector);</li>
     *   <li>8 bits ou RGB: 255;</li>
     *   <li>16 bits sem DICOM: menor profundidade padrao (10/12/14/16 bits)
     *       cujo fundo de escala contem o maximo observado;</li>
     *   <li>32 bits: 1 se a imagem ja estiver em [0,1]; senao, o proprio maximo.</li>
     * </ol>
     *
     * @param choice opcao de {@link #NORM_OPTIONS} (identificada pelo prefixo)
     * @param imp    imagem de entrada (para profundidade de bits e cabecalho DICOM)
     * @param max    maior valor de pixel entre as fatias processadas
     * @param origin array de tamanho 1; em {@code origin[0]} e gravada uma
     *               descricao legivel de como o divisor foi obtido (vai para a tabela)
     * @return o fundo de escala (divisor); pode ser &lt;= 0 se {@code max} for
     *         invalido, o que o chamador trata como erro
     */
    private static double normalizationMax(String choice, ImagePlus imp, double max, String[] origin) {
        origin[0] = choice;
        if (choice.startsWith("8 bits")) return 255;
        if (choice.startsWith("10 bits")) return 1023;
        if (choice.startsWith("12 bits")) return 4095;
        if (choice.startsWith("14 bits")) return 16383;
        if (choice.startsWith("16 bits")) return 65535;
        if (choice.startsWith("Nenhuma")) return 1;
        if (choice.startsWith("Maximo")) return max;

        // Automatico (ver regras no Javadoc acima)
        int bits = dicomBitsStored(imp);
        if (bits >= 1 && bits <= 16 && imp.getBitDepth() == 16) {
            origin[0] = "Automatico: DICOM Bits Stored = " + bits;
            return Math.pow(2, bits) - 1;
        }
        switch (imp.getBitDepth()) {
            case 8:
            case 24:   // RGB
                origin[0] = "Automatico: 8 bits";
                return 255;
            case 16:
                origin[0] = "Automatico: pelo maximo (" + (long) max + ")";
                if (max <= 1023) return 1023;
                if (max <= 4095) return 4095;
                if (max <= 16383) return 16383;
                return 65535;
            default:   // 32 bits (float)
                origin[0] = max <= 1.0 ? "Automatico: ja em 0-1" : "Automatico: maximo da imagem";
                return max <= 1.0 ? 1.0 : max;
        }
    }

    /**
     * Le o Bits Stored (tag DICOM 0028,0101), isto e, quantos bits de cada
     * pixel sao efetivamente usados pelo detector.
     *
     * <p>O cabecalho pode ter varios Bits Stored: mamografias Hologic trazem,
     * dentro de sequencias (ex.: Contributing Sources Sequence), os atributos
     * de outras imagens, e eles aparecem antes dos da imagem aberta. Por isso
     * usa-se o Bits Stored cujos Rows/Columns (0028,0010/0011) mais recentes
     * batem com o tamanho da imagem; sem correspondencia, vale o ultimo.
     * ({@code DicomTools.getTag} devolveria o primeiro, que pode ser o errado.)
     *
     * @param imp imagem de entrada
     * @return o numero de bits, ou -1 se a imagem nao for DICOM, a tag nao
     *         existir ou o valor nao for um inteiro valido
     */
    private static int dicomBitsStored(ImagePlus imp) {
        String info = null;
        if (imp.getStackSize() > 1)
            info = imp.getStack().getSliceLabel(imp.getCurrentSlice());
        if (info == null || !info.contains("0028,"))
            info = imp.getInfoProperty();
        if (info == null) return -1;

        int rows = -1, cols = -1, match = -1, last = -1;
        for (String line : info.split("\n")) {
            line = line.trim();
            if (line.length() < 9 || !line.startsWith("0028,")) continue;
            int v = tagInt(line);
            String tag = line.substring(0, 9);
            if (tag.equals("0028,0010")) rows = v;
            else if (tag.equals("0028,0011")) cols = v;
            else if (tag.equals("0028,0101") && v > 0) {
                last = v;
                if (rows == imp.getHeight() && cols == imp.getWidth()) match = v;
            }
        }
        return match > 0 ? match : last;
    }

    /** Valor inteiro de uma linha "gggg,eeee  Nome: valor", ou -1. */
    private static int tagInt(String line) {
        int i = line.indexOf(':');
        if (i < 0) return -1;
        try {
            return Integer.parseInt(line.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Calcula o mapa de desvio padrao local amostral (divisor n-1 = 8) em
     * janela 3x3, com padding simetrico nas bordas.
     *
     * <p>Para uma janela 3x3 o espelhamento simetrico ({@code padarray(...,
     * 'symmetric')} do MATLAB) equivale a repetir o pixel de borda, que e o
     * que os indices limitados a {@code [0, w-1]} e {@code [0, h-1]} fazem.
     *
     * <p>Janelas totalmente uniformes recebem 0 exato (ver comentario no
     * codigo), o que importa porque o ICC so considera valores &gt; 0.
     *
     * <p>Visibilidade de pacote (nao {@code private}) para permitir testes.
     *
     * @param px pixels da imagem em ordem de linhas, na escala original
     * @param w  largura da imagem
     * @param h  altura da imagem
     * @param k  fator de normalizacao aplicado a cada pixel ({@code 1 / fundo de escala})
     * @return mapa de desvios padroes, com o mesmo tamanho e ordem de {@code px}
     */
    static float[] stdMap3x3(float[] px, int w, int h, double k) {
        float[] out = new float[w * h];
        double[] win = new double[9];   // valores normalizados da janela atual
        for (int y = 0; y < h; y++) {
            // Linhas vizinhas, repetindo a borda nas extremidades
            int y0 = y > 0 ? y - 1 : 0, y2 = y < h - 1 ? y + 1 : h - 1;
            int[] rows = {y0 * w, y * w, y2 * w};   // deslocamentos de inicio de linha
            for (int x = 0; x < w; x++) {
                // Colunas vizinhas, repetindo a borda nas extremidades
                int x0 = x > 0 ? x - 1 : 0, x2 = x < w - 1 ? x + 1 : w - 1;
                int[] cols = {x0, x, x2};
                double mean = 0;
                int n = 0;
                boolean uniform = true;
                float p0 = px[rows[0] + cols[0]];
                for (int r : rows)
                    for (int c : cols) {
                        float p = px[r + c];
                        if (p != p0) uniform = false;
                        double v = p * k;
                        win[n++] = v;
                        mean += v;
                    }
                // Janela sem variacao: dp = 0 exato (evita residuo de arredondamento
                // que faria a regiao entrar indevidamente em numel).
                if (uniform) { out[y * w + x] = 0f; continue; }
                mean /= 9.0;
                // Soma dos quadrados dos desvios (algoritmo em duas passadas,
                // numericamente mais estavel que E[x^2] - E[x]^2)
                double ss = 0;
                for (double v : win) {
                    double d = v - mean;
                    ss += d * d;
                }
                out[y * w + x] = (float) Math.sqrt(ss / 8.0);   // n - 1 = 8
            }
        }
        return out;
    }

    /**
     * Verifica se o pixel (x, y) da imagem pertence a ROI.
     *
     * @param x    coluna do pixel, em coordenadas da imagem
     * @param y    linha do pixel, em coordenadas da imagem
     * @param rb   retangulo envolvente da ROI
     * @param mask mascara da ROI em coordenadas relativas a {@code rb}
     *             (valor != 0 = dentro), ou {@code null} para ROI retangular
     * @return {@code true} se o pixel estiver dentro da ROI
     */
    private static boolean insideRoi(int x, int y, java.awt.Rectangle rb, ImageProcessor mask) {
        if (x < rb.x || y < rb.y || x >= rb.x + rb.width || y >= rb.y + rb.height) return false;
        return mask == null || mask.getPixel(x - rb.x, y - rb.y) != 0;
    }
}
