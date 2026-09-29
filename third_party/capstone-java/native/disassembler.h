#include "reg_mapping.h"

typedef struct capstone {
  bool is64Bit;
  csh handle;
  map_reg map2U;
  map_reg map2C;
} *t_capstone;
