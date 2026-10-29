-- Orientação opcional do estudante para o conteúdo gerado do tópico (foco, pontos a cobrir, banca ou vestibular de referência).
ALTER TABLE topico ADD COLUMN orientacao text CHECK (orientacao IS NULL OR char_length(orientacao) BETWEEN 1 AND 500);
