# Índice Carneiro de Contraste (ICC): plugin para ImageJ

Plugin para ImageJ/Fiji que calcula o **Índice Carneiro de Contraste (ICC)**, em inglês *Carneiro Contrast Index (CCI)*. É uma métrica global de contraste obtida a partir de desvios padrões locais em janelas 3x3. O método segue o **Capítulo 6** (Eq. 26 e Figura 26) da tese:

> CARNEIRO, P. C. *Proposta de metodologia para realce de contraste em imagens de mamas densas.*

O índice dá dois resultados:
- **um número**, o ICC da imagem. Quanto maior, maior o contraste global;
- **um mapa visual** com os desvios padrões locais. Regiões claras têm mais contraste.

Diferente do EME, o ICC **não precisa de região de interesse**: é calculado sobre a imagem inteira.

---

## Início rápido (< 5 min)

1. Copie `Indice_Carneiro_Contraste.java` para a pasta `plugins` do ImageJ (ou `Fiji.app/plugins`).
2. No ImageJ, abra **Plugins › Compile and Run…** e selecione o arquivo.
3. Abra uma mamografia e rode **Plugins › Indice Carneiro Contraste**.
4. Clique em **OK**. O ICC aparece na tabela **"ICC"** e o mapa abre em uma nova janela.

A partir da segunda vez, o plugin já aparece direto no menu **Plugins**.

> **Requisitos:** ImageJ 1.52 ou mais recente, ou Fiji (testado no ImageJ 1.54, Java 8). A compilação usa o Java que vem com o ImageJ.

---

## Opções

| Opção | Padrão | Descrição |
|---|---|---|
| **Normalização (fundo de escala)** | Automático | Valor que divide os pixels para levá-los a [0,1]. Veja a tabela abaixo. |
| **Constante multiplicadora** | 1000 | Valor usado na tese para facilitar a leitura do índice. |
| **Mostrar mapa de desvios padrões** | ligado | Abre o mapa como imagem de 32 bits. |
| **Calcular todas as fatias** | desligado | Só aparece em pilhas de imagens. Calcula um ICC por fatia (tomossíntese). |
| **Média apenas dentro da ROI** | desligado | Só aparece se houver uma ROI de área. O mapa é calculado na imagem toda e a média só dentro da ROI. |

### Normalização

| Escolha | Divide por | Uso típico |
|---|---|---|
| Automático | DICOM 16 bits com *Bits Stored* (0028,0101) → 2^bits − 1. Sem a tag: 8 bits → 255; 16 bits → 1023, 4095, 16383 ou 65535 (o menor que contém o máximo); 32 bits → 1 se já estiver em [0,1], senão o máximo | uso geral |
| 10 bits | 1023 | tomossíntese |
| 12 bits | 4095 | mamografia digital |
| 8 / 14 / 16 bits | 255 / 16383 / 65535 | outros detectores |
| Máximo da imagem | maior pixel da imagem | imagens sem fundo de escala conhecido |
| Nenhuma | 1 | imagem já normalizada |

Em pilhas, **o mesmo fundo de escala vale para todas as fatias** (o máximo é procurado na pilha inteira), para que os ICCs das fatias sejam comparáveis. Se algum pixel passar do fundo de escala escolhido, o plugin escreve um aviso no *Log*.

> ⚠️ **Para comparar a imagem original com a processada, fixe a mesma normalização nas duas** (por exemplo, 12 bits). No modo Automático, uma imagem processada com máximo diferente pode cair em outro fundo de escala, e os valores de ICC deixam de ser comparáveis. Exportações de 8 bits (por exemplo, TIFF salvo pela estação de trabalho) também não são comparáveis com o DICOM original de 10/12 bits.

### Saída

Tabela **"ICC"**: uma linha por fatia. Execuções seguidas vão acrescentando linhas, o que facilita comparar imagens.

| Coluna | Significado |
|---|---|
| Imagem | título da imagem |
| Fatia | número da fatia |
| Fundo de escala | valor usado na normalização |
| Origem da escala | de onde veio o fundo de escala (opção fixa, tag DICOM, máximo da imagem...) |
| numel (dp>0) | quantidade de pixels do mapa com desvio padrão > 0 |
| ICC | índice final |

Janela **`Mapa_DP_<imagem>`**: mapa de desvios padrões (já normalizado, sem multiplicar pela constante), com a mesma calibração espacial da imagem original.

---

## Uso em macro

O plugin pode ser gravado com *Plugins › Macros › Record…*. Exemplos:

```javascript
// Uma imagem de mamografia 12 bits, sem mapa
run("Indice Carneiro Contraste", "normalizacao=[12 bits (4095)] constante=1000");

// Stack de tomossíntese, todas as fatias, com mapa
run("Indice Carneiro Contraste", "normalizacao=[10 bits (1023)] constante=1000 mostrar calcular");
```

Comparar original e processada em lote:

```javascript
dir = getDirectory("Pasta com as imagens");
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    open(dir + list[i]);
    run("Indice Carneiro Contraste", "normalizacao=[12 bits (4095)] constante=1000");
    close();
}
// A tabela "ICC" fica com uma linha por imagem; salve com File › Save As.
```

---

## Algoritmo

Para cada pixel $(x,y)$ da imagem normalizada $f$:

$$
dp_i = \left[\frac{1}{8}\sum_{m=-1}^{1}\sum_{n=-1}^{1}\big(f(x+m,\,y+n)-\mu_k\big)^2\right]^{1/2}
$$

$$
ICC = \frac{1000}{numel}\sum_{dp_i>0} dp_i
$$

- $\mu_k$ é a média dos 9 pixels da janela.
- $numel$ é o número de pixels com $dp_i > 0$. Regiões sem nenhuma variação, como o fundo preto, ficam fora da média.
- **Bordas:** padding simétrico (espelhado). Em janela 3x3, isso equivale a repetir o pixel da borda.
- **Desvio padrão amostral ($n-1 = 8$).** A tese não diz qual divisor usa. Escolhemos $n-1$ porque é o único que reproduz os valores da Figura 26, e é o mesmo comportamento do `stdfilt` do MATLAB. Veja **Validação**.

Fluxo: `imagem → ÷ fundo de escala → desvio padrão 3x3 → mapa → média dos valores > 0 → × 1000`

---

## Validação

Usamos a matriz 6×5 de exemplo da Figura 26 (12 bits, ÷ 4095).

| | Resultado |
|---|---|
| Mapa com $n-1$ | `0,08 0,08 0,08 0,07 0,04 / 0,10 0,08 0,08 0,07 0,07 / 0,11 0,10 0,08 0,11 0,16 / …`: **igual à figura** |
| Mapa com $n$ | `0,08 0,07 0,07 0,06 0,04 / 0,09 …`: diferente da figura |
| ICC do exemplo | **87,33** |

O plugin foi testado no ImageJ 1.54 (Java 8) em modo *batch*:

| Teste | Resultado |
|---|---|
| Matriz da Figura 26, 32 bits, 12 bits (4095) | mapa igual à figura, numel = 29, **ICC = 87,325** |
| Mesma matriz, 16 bits, Automático | ICC = 87,325 |
| Imagem constante (16 bits = 1000; 32 bits = 0,3) | numel = 0, ICC = 0 |
| Pilha de 2 fatias com contraste 4× maior na fatia 2 | mesmo fundo de escala; ICC da fatia 2 ≈ 4× o da fatia 1 |
| Fatia de tomossíntese Hologic (DICOM, Bits Stored = 10) | escala 1023 lida da tag; ICC ≈ 19,3, na faixa da Tabela 11 da tese (Hologic 3D originais: 20,80 ± 2,77) |

Para repetir: crie uma imagem 32 bits 5×6 com os valores da Figura 26, rode com normalização **12 bits** e confira se o ICC dá **≈ 87,33**.

---

## Limitações

- A janela é fixa em 3x3, como na tese.
- Imagens coloridas (RGB e 8 bits com tabela de cores) são convertidas para tons de cinza com os mesmos pesos do `im2gray` do MATLAB (0,2989 R + 0,5870 G + 0,1140 B), e não com a média simples padrão do ImageJ. O índice foi pensado para tons de cinza.
- Pixels com desvio padrão exatamente 0 (janela 3x3 com os nove valores iguais) são excluídos da média. Ruído quase nulo, mas diferente de zero, entra no cálculo.
- A tag *Bits Stored* só é usada no modo Automático e em imagens de 16 bits; imagens processadas e salvas em outro formato perdem a tag.
- Mamografias Hologic podem ter mais de um *Bits Stored* no cabeçalho (por exemplo, 14 bits da imagem de origem dentro da *Contributing Sources Sequence* e 10 bits da imagem aberta). O plugin usa o *Bits Stored* cujas *Rows*/*Columns* batem com o tamanho da imagem.

---

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `Indice_Carneiro_Contraste.java` | código-fonte do plugin (classe única, sem dependências além do `ij.jar`) |
| `README.md` | este documento |

## Contribuindo

- Mudanças no cálculo devem continuar reproduzindo a Figura 26 (ICC ≈ 87,33 no exemplo).
- O núcleo do algoritmo é o método `stdMap3x3`. A normalização fica em `normalizationMax`.
- Mantenha os rótulos do diálogo estáveis, porque eles definem as chaves usadas nas macros (`normalizacao`, `constante`, `mostrar`, `calcular`, `media`).

## Como citar

Ao usar o índice em trabalhos, cite a tese original de P. C. Carneiro (Capítulo 6, Índice Carneiro de Contraste).
