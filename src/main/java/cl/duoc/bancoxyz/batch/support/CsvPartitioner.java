package cl.duoc.bancoxyz.batch.support;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Divide un CSV en rangos de registros, excluyendo la cabecera. */
public final class CsvPartitioner implements Partitioner {
    private final Resource resource;
    private final int gridSize;

    public CsvPartitioner(Resource resource, int gridSize) {
        this.resource = resource;
        this.gridSize = gridSize;
    }

    @Override
    public Map<String, ExecutionContext> partition(int ignoredGridSize) {
        int total = contarRegistros();
        int partitions = Math.min(gridSize, Math.max(total, 1));
        int base = total / partitions;
        int remainder = total % partitions;
        int start = 0;
        Map<String, ExecutionContext> result = new LinkedHashMap<>();

        for (int i = 0; i < partitions; i++) {
            int size = base + (i < remainder ? 1 : 0);
            ExecutionContext context = new ExecutionContext();
            context.putInt("start", start);
            context.putInt("end", start + size);
            context.putInt("partition", i);
            result.put("partition" + i, context);
            start += size;
        }
        return result;
    }

    private int contarRegistros() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();
            int count = 0;
            while (reader.readLine() != null) {
                count++;
            }
            return count;
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible contar los registros de " + resource, exception);
        }
    }
}
