module prf_rounding #(
    parameter N = 2048,
    parameter P = 32,
    parameter ACC_WIDTH = 32
) (
    input wire [ACC_WIDTH-1:0] inner_product,
    output wire [$clog2(P)-1:0] prf_out
);

    localparam LOG2_N = $clog2(N);
    localparam LOG2_2N = LOG2_N + 1;
    localparam LOG2_P = $clog2(P);
    localparam SHIFT = LOG2_N - LOG2_P;

    wire [LOG2_2N-1:0] inner_mod_2N;
    assign inner_mod_2N = inner_product[LOG2_2N-1:0];

    wire msb;
    // check if inner_mod_2N >= N -> check MSB of innner_mod_2N
    assign msb = inner_mod_2N[LOG2_N];

    wire [LOG2_N-1:0] inner_mod_N;
    assign inner_mod_N = inner_product[LOG2_N-1:0];

    wire [LOG2_P-1:0] rounded;
    assign rounded = inner_mod_N[LOG2_N-1:SHIFT]; // inner_mod_N * P // N = upper bits using shift

    // negate if msb is 1
    wire [LOG2_P-1:0] negated;
    assign negated = P - rounded;

    assign prf_out = msb ? negated : rounded;


endmodule