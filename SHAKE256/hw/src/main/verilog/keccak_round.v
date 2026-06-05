module keccak_round (
    input  wire [1599:0] state_in,
    input  wire [4:0]   round,
    output wire [1599:0] state_out
);

    // Unpack state
    wire [63:0] s [0:24];

    genvar si;
    generate
        for (si = 0; si < 25; si = si + 1) begin : state_unpack
            assign s[si] = state_in[si*64 +: 64];
        end
    endgenerate

    function [63:0] rotl;
        input [63:0] x;
        input [5:0]  n;
        begin
            rotl = (x << n) | (x >> (6'd64 - n));
        end
    endfunction


    // Round constant lookup
    reg [63:0] rc;

    always @(*) begin
        case (round)
            5'd0:   rc = 64'h0000000000000001;
            5'd1:   rc = 64'h0000000000008082;
            5'd2:   rc = 64'h800000000000808A;
            5'd3:   rc = 64'h8000000080008000;
            5'd4:   rc = 64'h000000000000808B;
            5'd5:   rc = 64'h0000000080000001;
            5'd6:   rc = 64'h8000000080008081;
            5'd7:   rc = 64'h8000000000008009;
            5'd8:   rc = 64'h000000000000008A;
            5'd9:   rc = 64'h0000000000000088;
            5'd10:  rc = 64'h0000000080008009;
            5'd11:  rc = 64'h000000008000000A;
            5'd12:  rc = 64'h000000008000808B;
            5'd13:  rc = 64'h800000000000008B;
            5'd14:  rc = 64'h8000000000008089;
            5'd15:  rc = 64'h8000000000008003;
            5'd16:  rc = 64'h8000000000008002;
            5'd17:  rc = 64'h8000000000000080;
            5'd18:  rc = 64'h000000000000800A;
            5'd19:  rc = 64'h800000008000000A;
            5'd20:  rc = 64'h8000000080008081;
            5'd21:  rc = 64'h8000000000008080;
            5'd22:  rc = 64'h0000000080000001;
            5'd23:  rc = 64'h8000000080008008;
            default:rc = 64'h0;
        endcase
    end

    // Theta
    wire [63:0] C [0:4];
    assign C[0] = s[0] ^ s[5] ^ s[10] ^ s[15] ^ s[20];
    assign C[1] = s[1] ^ s[6] ^ s[11] ^ s[16] ^ s[21];
    assign C[2] = s[2] ^ s[7] ^ s[12] ^ s[17] ^ s[22];
    assign C[3] = s[3] ^ s[8] ^ s[13] ^ s[18] ^ s[23];
    assign C[4] = s[4] ^ s[9] ^ s[14] ^ s[19] ^ s[24];

    wire [63:0] t [0:4];
    assign t[0] = C[4] ^ {C[1][62:0], C[1][63]};
    assign t[1] = C[0] ^ {C[2][62:0], C[2][63]};
    assign t[2] = C[1] ^ {C[3][62:0], C[3][63]};
    assign t[3] = C[2] ^ {C[4][62:0], C[4][63]};
    assign t[4] = C[3] ^ {C[0][62:0], C[0][63]};

    wire [63:0] theta [0:24];
    genvar ti;
    generate
        for (ti = 0; ti < 25; ti = ti + 1) begin : theta_loop
            assign theta[ti] = s[ti] ^ t[ti % 5];
        end
    endgenerate

    // Rho & Pi
    function [63:0] rho_rot;
        input [4:0] index;
        begin
            case (index)
                5'd0:  rho_rot = 6'd0;
                5'd1:  rho_rot = 6'd44;
                5'd2:  rho_rot = 6'd43;
                5'd3:  rho_rot = 6'd21;
                5'd4:  rho_rot = 6'd14;
                5'd5:  rho_rot = 6'd28;
                5'd6:  rho_rot = 6'd20;
                5'd7:  rho_rot = 6'd3;
                5'd8:  rho_rot = 6'd45;
                5'd9:  rho_rot = 6'd61;
                5'd10: rho_rot = 6'd1;
                5'd11: rho_rot = 6'd6;
                5'd12: rho_rot = 6'd25;
                5'd13: rho_rot = 6'd8;
                5'd14: rho_rot = 6'd18;
                5'd15: rho_rot = 6'd27;
                5'd16: rho_rot = 6'd36;
                5'd17: rho_rot = 6'd10;
                5'd18: rho_rot = 6'd15;
                5'd19: rho_rot = 6'd56;
                5'd20: rho_rot = 6'd62;
                5'd21: rho_rot = 6'd55;
                5'd22: rho_rot = 6'd39;
                5'd23: rho_rot = 6'd41;
                5'd24: rho_rot = 6'd2;
                default: rho_rot = 6'd0;
            endcase
        end
    endfunction

    function [4:0] pi_lane;
        input [4:0] pos;
        begin
            case (pos)
                5'd0:  pi_lane = 5'd0;
                5'd1:  pi_lane = 5'd6;
                5'd2:  pi_lane = 5'd12;
                5'd3:  pi_lane = 5'd18;
                5'd4:  pi_lane = 5'd24;
                5'd5:  pi_lane = 5'd3;
                5'd6:  pi_lane = 5'd9;
                5'd7:  pi_lane = 5'd10;
                5'd8:  pi_lane = 5'd16;
                5'd9:  pi_lane = 5'd22;
                5'd10: pi_lane = 5'd1;
                5'd11: pi_lane = 5'd7;
                5'd12: pi_lane = 5'd13;
                5'd13: pi_lane = 5'd19;
                5'd14: pi_lane = 5'd20;
                5'd15: pi_lane = 5'd4;
                5'd16: pi_lane = 5'd5;
                5'd17: pi_lane = 5'd11;
                5'd18: pi_lane = 5'd17;
                5'd19: pi_lane = 5'd23;
                5'd20: pi_lane = 5'd2;
                5'd21: pi_lane = 5'd8;
                5'd22: pi_lane = 5'd14;
                5'd23: pi_lane = 5'd15;
                5'd24: pi_lane = 5'd21;
                default: pi_lane = 5'd0;
            endcase
        end
    endfunction


    wire [63:0] rho_pi [0:24];

    genvar rpi;
    generate
        for (rpi = 0; rpi < 25; rpi = rpi + 1) begin : rho_pi_loop
            assign rho_pi[rpi] = rotl(theta[pi_lane(rpi)], rho_rot(rpi));
        end
    endgenerate

    // Chi
    wire [63:0] chi [0:24];
    genvar x,y,r;
    generate
        for (y = 0; y < 5; y = y + 1) begin : chi_y
            wire [63:0] row [0:4];
            for (x = 0; x < 5; x = x + 1) begin : chi_row
                assign row[x] = rho_pi[y * 5 + x];
            end
            for (x = 0; x < 5; x = x + 1) begin : chi_x
                assign chi[y * 5 + x] = row[x] ^ (~row[(x+1) % 5] & row[(x+2) % 5]);
            end
        end
    endgenerate
    
    // Iota
    wire [63:0] iota [0:24];
    assign iota[0]  = chi[0] ^ rc;
    genvar ii;
    generate
        for (ii = 1; ii < 25; ii = ii + 1) begin : iota_loop
            assign iota[ii] = chi[ii];
        end
    endgenerate

    // Pack state out
    genvar so;
    generate
        for (so = 0; so < 25; so = so + 1) begin : state_pack
            assign state_out[so*64 +: 64] = iota[so];
        end
    endgenerate

endmodule