// Autores: Gabrielle Guarani da Silva e Gustavo Filipi Lopes Machado

import java.util.*;

public class SimulacaoFilas {

    // ===================== GERADOR DE ALEATÓRIOS =====================

    static long seed = 12345L;
    static int totalRandomsUsed = 0;
    static final int MAX_RANDOMS = 100_000;

    // Retorna um número aleatório uniforme em [0,1). Incrementa o contador global.
    static double nextRandom() {
        // LCG padrão (Numerical Recipes)
        seed = (seed * 1664525L + 1013904223L) & 0xFFFFFFFFL;
        totalRandomsUsed++;
        return (seed & 0x7FFFFFFF) / (double) 0x80000000L;
    }

    // Uniforme [a, b]
    static double uniform(double a, double b) {
        return a + (b - a) * nextRandom();
    }

    // ===================== TIPOS DE EVENTOS =====================

    static final int EVT_CHEGADA_FILA1   = 1;
    static final int EVT_SAIDA_FILA1     = 2;
    static final int EVT_SAIDA_FILA2_S1  = 3;  // servidor 1 da Fila 2
    static final int EVT_SAIDA_FILA2_S2  = 4;  // servidor 2 da Fila 2
    static final int EVT_SAIDA_FILA3_S1  = 5;  // servidor 1 da Fila 3
    static final int EVT_SAIDA_FILA3_S2  = 6;  // servidor 2 da Fila 3

    // ===================== ESTRUTURA DE EVENTO =====================

    static class Evento implements Comparable<Evento> {
        double tempo;
        int tipo;
        Evento(double tempo, int tipo) {
            this.tempo = tempo;
            this.tipo = tipo;
        }
        @Override
        public int compareTo(Evento o) {
            return Double.compare(this.tempo, o.tempo);
        }
    }

    // ===================== ESTADO DAS FILAS =====================

    // Fila 1: G/G/1 (sem limite prático de capacidade -> usaremos Integer.MAX_VALUE)
    static int fila1_na_fila   = 0; // clientes aguardando
    static boolean fila1_servidor_ocupado = false;
    static double  fila1_tempo_livre_servidor = 0.0;

    // Fila 2: G/G/2/5 (2 servidores, capacidade total 5 = fila + em serviço)
    static final int CAP_FILA2 = 5;
    static int    fila2_na_fila = 0;
    static boolean[] fila2_servidor_ocupado = {false, false};
    static double[]  fila2_tempo_livre_servidor = {0.0, 0.0};

    // Fila 3: G/G/2/10 (2 servidores, capacidade total 10)
    static final int CAP_FILA3 = 10;
    static int    fila3_na_fila = 0;
    static boolean[] fila3_servidor_ocupado = {false, false};
    static double[]  fila3_tempo_livre_servidor = {0.0, 0.0};

    // ===================== ESTATÍSTICAS =====================

    // Perdas por fila
    static long perdas_fila2 = 0;
    static long perdas_fila3 = 0;

    // Acumuladores de tempo por estado para cada fila
    // Fila 1: estado = número de clientes no sistema (fila + em serviço), 0..N
    // Filas 2 e 3: estado = número de clientes no sistema, 0..cap
    static final int MAX_ESTADO = 20; // para fila 1, sem limite, mas registramos até 20
    static double[] tempoEstado_fila1 = new double[MAX_ESTADO + 1];
    static double[] tempoEstado_fila2 = new double[CAP_FILA2 + 1];
    static double[] tempoEstado_fila3 = new double[CAP_FILA3 + 1];

    // Instante do último evento para cálculo de acumuladores
    static double tempoAnterior = 0.0;

    // Tempo global da simulação
    static double tempoAtual = 0.0;

    // Total de clientes que completaram serviço (saíram do sistema)
    static long clientesSaidosSistema = 0;
    static long clientesAtendidosFila1 = 0;
    static long clientesAtendidosFila2 = 0;
    static long clientesAtendidosFila3 = 0;

    // Fila de eventos
    static PriorityQueue<Evento> agenda = new PriorityQueue<>();

    // ===================== AUXILIARES =====================

    // Número de clientes no sistema da Fila 1
    static int estadoFila1() {
        return fila1_na_fila + (fila1_servidor_ocupado ? 1 : 0);
    }

    // Número de clientes no sistema da Fila 2
    static int estadoFila2() {
        int serv = (fila2_servidor_ocupado[0] ? 1 : 0) + (fila2_servidor_ocupado[1] ? 1 : 0);
        return fila2_na_fila + serv;
    }

    // Número de clientes no sistema da Fila 3
    static int estadoFila3() {
        int serv = (fila3_servidor_ocupado[0] ? 1 : 0) + (fila3_servidor_ocupado[1] ? 1 : 0);
        return fila3_na_fila + serv;
    }

    // Acumula tempo nos estados atuais antes de mudar o estado
    static void acumularTempo(double novoTempo) {
        double delta = novoTempo - tempoAnterior;
        if (delta < 0) delta = 0;

        int e1 = estadoFila1();
        int e2 = estadoFila2();
        int e3 = estadoFila3();

        if (e1 <= MAX_ESTADO) tempoEstado_fila1[e1] += delta;
        else                  tempoEstado_fila1[MAX_ESTADO] += delta; // overflow -> acumula no último

        if (e2 <= CAP_FILA2) tempoEstado_fila2[e2] += delta;
        if (e3 <= CAP_FILA3) tempoEstado_fila3[e3] += delta;

        tempoAnterior = novoTempo;
    }

    // ===================== LÓGICA DE CHEGADA/SAÍDA =====================

    // Tenta alocar um servidor disponível na Fila 2. Retorna índice (0 ou 1) ou -1.
    static int servidorLivreFila2() {
        if (!fila2_servidor_ocupado[0]) return 0;
        if (!fila2_servidor_ocupado[1]) return 1;
        return -1;
    }

    // Tenta alocar um servidor disponível na Fila 3. Retorna índice (0 ou 1) ou -1.
    static int servidorLivreFila3() {
        if (!fila3_servidor_ocupado[0]) return 0;
        if (!fila3_servidor_ocupado[1]) return 1;
        return -1;
    }

    // Processa chegada de cliente na Fila 1
    static void chegadaFila1() {
        acumularTempo(tempoAtual);

        if (!fila1_servidor_ocupado) {
            // Servidor livre -> atende diretamente
            fila1_servidor_ocupado = true;
            double ts = uniform(1.0, 2.0);
            agenda.add(new Evento(tempoAtual + ts, EVT_SAIDA_FILA1));
        } else {
            fila1_na_fila++;
        }

        // Agenda próxima chegada externa (se ainda há randoms)
        if (totalRandomsUsed < MAX_RANDOMS) {
            double ti = uniform(2.0, 4.0);
            agenda.add(new Evento(tempoAtual + ti, EVT_CHEGADA_FILA1));
        }
    }

    // Processa saída da Fila 1
    static void saidaFila1() {
        acumularTempo(tempoAtual);
        clientesAtendidosFila1++;

        // Decide destino: 80% Fila 2, 20% Fila 3
        double r = nextRandom();
        if (r < 0.80) {
            tentarEntrarFila2();
        } else {
            tentarEntrarFila3();
        }

        // Próximo cliente na fila 1?
        if (fila1_na_fila > 0) {
            fila1_na_fila--;
            double ts = uniform(1.0, 2.0);
            agenda.add(new Evento(tempoAtual + ts, EVT_SAIDA_FILA1));
        } else {
            fila1_servidor_ocupado = false;
        }
    }

    // Tenta inserir cliente na Fila 2
    static void tentarEntrarFila2() {
        int cap_atual = estadoFila2();
        if (cap_atual >= CAP_FILA2) {
            // Capacidade máxima atingida -> perda
            perdas_fila2++;
            return;
        }
        int sv = servidorLivreFila2();
        if (sv >= 0) {
            fila2_servidor_ocupado[sv] = true;
            double ts = uniform(4.0, 6.0);
            int evt = (sv == 0) ? EVT_SAIDA_FILA2_S1 : EVT_SAIDA_FILA2_S2;
            fila2_tempo_livre_servidor[sv] = tempoAtual + ts;
            agenda.add(new Evento(tempoAtual + ts, evt));
        } else {
            fila2_na_fila++;
        }
    }

    // Tenta inserir cliente na Fila 3
    static void tentarEntrarFila3() {
        int cap_atual = estadoFila3();
        if (cap_atual >= CAP_FILA3) {
            perdas_fila3++;
            return;
        }
        int sv = servidorLivreFila3();
        if (sv >= 0) {
            fila3_servidor_ocupado[sv] = true;
            double ts = uniform(5.0, 15.0);
            int evt = (sv == 0) ? EVT_SAIDA_FILA3_S1 : EVT_SAIDA_FILA3_S2;
            fila3_tempo_livre_servidor[sv] = tempoAtual + ts;
            agenda.add(new Evento(tempoAtual + ts, evt));
        } else {
            fila3_na_fila++;
        }
    }

    // Processa saída de um servidor da Fila 2
    static void saidaFila2(int servidor) {
        acumularTempo(tempoAtual);
        clientesAtendidosFila2++;

        // Decide destino: 30% -> Fila 1, 70% sai do sistema
        double r = nextRandom();
        if (r < 0.30) {
            tentarEntrarFila1();
        } else {
            clientesSaidosSistema++;
        }

        // Próximo cliente na fila de espera da Fila 2?
        if (fila2_na_fila > 0) {
            fila2_na_fila--;
            double ts = uniform(4.0, 6.0);
            int evt = (servidor == 0) ? EVT_SAIDA_FILA2_S1 : EVT_SAIDA_FILA2_S2;
            fila2_tempo_livre_servidor[servidor] = tempoAtual + ts;
            agenda.add(new Evento(tempoAtual + ts, evt));
        } else {
            fila2_servidor_ocupado[servidor] = false;
        }
    }

    // Processa saída de um servidor da Fila 3
    static void saidaFila3(int servidor) {
        acumularTempo(tempoAtual);
        clientesAtendidosFila3++;

        // Decide destino: 70% -> Fila 1, 30% sai do sistema
        double r = nextRandom();
        if (r < 0.70) {
            tentarEntrarFila1();
        } else {
            clientesSaidosSistema++;
        }

        // Próximo cliente na fila de espera da Fila 3?
        if (fila3_na_fila > 0) {
            fila3_na_fila--;
            double ts = uniform(5.0, 15.0);
            int evt = (servidor == 0) ? EVT_SAIDA_FILA3_S1 : EVT_SAIDA_FILA3_S2;
            fila3_tempo_livre_servidor[servidor] = tempoAtual + ts;
            agenda.add(new Evento(tempoAtual + ts, evt));
        } else {
            fila3_servidor_ocupado[servidor] = false;
        }
    }

    // Inserção direta de cliente proveniente de outra fila na Fila 1
    static void tentarEntrarFila1() {
        acumularTempo(tempoAtual);
        if (!fila1_servidor_ocupado) {
            fila1_servidor_ocupado = true;
            double ts = uniform(1.0, 2.0);
            agenda.add(new Evento(tempoAtual + ts, EVT_SAIDA_FILA1));
        } else {
            fila1_na_fila++;
        }
    }

    // ===================== IMPRESSÃO DOS RESULTADOS =====================

    static void imprimirResultados() {
        double tempoTotal = tempoAtual;

        System.out.println("=================================================================");
        System.out.println("       SIMULAÇÃO DE REDE DE FILAS - RESULTADOS FINAIS");
        System.out.println("=================================================================");
        System.out.printf("Tempo global da simulação : %.4f minutos%n", tempoTotal);
        System.out.printf("Números aleatórios usados : %d%n", totalRandomsUsed);
        System.out.printf("Clientes atendidos Fila 1 : %d%n", clientesAtendidosFila1);
        System.out.printf("Clientes atendidos Fila 2 : %d%n", clientesAtendidosFila2);
        System.out.printf("Clientes atendidos Fila 3 : %d%n", clientesAtendidosFila3);
        System.out.printf("Clientes que saíram do sistema: %d%n", clientesSaidosSistema);
        System.out.println();

        // ---- FILA 1 ----
        System.out.println("-----------------------------------------------------------------");
        System.out.println("FILA 1  (G/G/1 | chegadas 2..4min | serviço 1..2min)");
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("Perdas: 0  (capacidade ilimitada)%n");
        System.out.println();
        System.out.printf("%-8s  %-18s  %-14s%n", "Estado", "Tempo Acumulado(min)", "Probabilidade");
        System.out.println("--------------------------------------------");
        double somaF1 = Arrays.stream(tempoEstado_fila1).sum();
        for (int i = 0; i <= MAX_ESTADO; i++) {
            if (tempoEstado_fila1[i] > 0 || i == 0) {
                String label = (i == MAX_ESTADO) ? String.format(">=%d", MAX_ESTADO) : String.valueOf(i);
                System.out.printf("%-8s  %-18.4f  %.6f%n",
                        label, tempoEstado_fila1[i],
                        somaF1 > 0 ? tempoEstado_fila1[i] / somaF1 : 0.0);
            }
        }

        // ---- FILA 2 ----
        System.out.println();
        System.out.println("-----------------------------------------------------------------");
        System.out.println("FILA 2  (G/G/2/5 | serviço 4..6min | cap=5)");
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("Perdas (clientes rejeitados): %d%n", perdas_fila2);
        System.out.println();
        System.out.printf("%-8s  %-18s  %-14s%n", "Estado", "Tempo Acumulado(min)", "Probabilidade");
        System.out.println("--------------------------------------------");
        double somaF2 = Arrays.stream(tempoEstado_fila2).sum();
        for (int i = 0; i <= CAP_FILA2; i++) {
            System.out.printf("%-8d  %-18.4f  %.6f%n",
                    i, tempoEstado_fila2[i],
                    somaF2 > 0 ? tempoEstado_fila2[i] / somaF2 : 0.0);
        }

        // ---- FILA 3 ----
        System.out.println();
        System.out.println("-----------------------------------------------------------------");
        System.out.println("FILA 3  (G/G/2/10 | serviço 5..15min | cap=10)");
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("Perdas (clientes rejeitados): %d%n", perdas_fila3);
        System.out.println();
        System.out.printf("%-8s  %-18s  %-14s%n", "Estado", "Tempo Acumulado(min)", "Probabilidade");
        System.out.println("--------------------------------------------");
        double somaF3 = Arrays.stream(tempoEstado_fila3).sum();
        for (int i = 0; i <= CAP_FILA3; i++) {
            System.out.printf("%-8d  %-18.4f  %.6f%n",
                    i, tempoEstado_fila3[i],
                    somaF3 > 0 ? tempoEstado_fila3[i] / somaF3 : 0.0);
        }

        System.out.println("=================================================================");
    }

    // ===================== MAIN =====================

    public static void main(String[] args) {
        System.out.println("Iniciando simulação...");
        System.out.printf("Critério de parada: %d números aleatórios%n%n", MAX_RANDOMS);

        // Primeiro evento: chegada no tempo 2,0
        // Contabilizamos o uso do aleatório da chegada inicial manualmente
        agenda.add(new Evento(2.0, EVT_CHEGADA_FILA1));

        // Loop principal
        while (!agenda.isEmpty() && totalRandomsUsed < MAX_RANDOMS) {
            Evento evt = agenda.poll();

            // Se os randoms acabaram durante o processamento do último evento,
            // ainda finalizamos o evento atual mas não geramos novos.
            tempoAtual = evt.tempo;

            switch (evt.tipo) {
                case EVT_CHEGADA_FILA1:
                    chegadaFila1();
                    break;
                case EVT_SAIDA_FILA1:
                    saidaFila1();
                    break;
                case EVT_SAIDA_FILA2_S1:
                    saidaFila2(0);
                    break;
                case EVT_SAIDA_FILA2_S2:
                    saidaFila2(1);
                    break;
                case EVT_SAIDA_FILA3_S1:
                    saidaFila3(0);
                    break;
                case EVT_SAIDA_FILA3_S2:
                    saidaFila3(1);
                    break;
            }
        }

        // Acumula o tempo final (caso o último evento não tenha gerado mais eventos)
        acumularTempo(tempoAtual);

        imprimirResultados();
    }
}
